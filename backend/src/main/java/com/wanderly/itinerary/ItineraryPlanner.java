package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.Place;
import com.wanderly.user.TravelPace;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-day itinerary generation — a constrained orienteering / TSP-with-time-windows problem
 * (design doc §11), solved heuristically:
 *
 * <ol>
 *   <li><b>Score</b> each candidate: rating/5, x1.5 if its category is one of the user's interests.</li>
 *   <li><b>Pool</b>: keep the best {@code days x maxStops x 1.5} so clustering isn't dominated by filler.</li>
 *   <li><b>Cluster</b> the pool into one geographic group per day (k-means).</li>
 *   <li><b>Route and schedule together</b> ({@link DayScheduler}): best-scored first, insert each place
 *       where the timetable stays feasible (opening hours, weekly closures, day end, a lunch break)
 *       and the day finishes earliest; then time-aware 2-opt and relocate.</li>
 *   <li><b>Spill over</b>: anything that didn't fit gets one more chance on whichever day can absorb
 *       it most cheaply.</li>
 *   <li><b>Lunch spot</b>: a well-rated food place near where you are when lunch starts, open then.</li>
 * </ol>
 *
 * Pure and deterministic (no I/O, no randomness), so it is covered by plain unit tests.
 */
@Component
public class ItineraryPlanner {

    static final String ALGORITHM =
            "score > k-means per day > time-window insertion with lunch > time-aware 2-opt/relocate > spill-over";
    private static final double POOL_FACTOR = 1.5;
    private static final double INTEREST_BOOST = 1.5;
    /** Lunch time includes getting there, so the suggestion has to be close. */
    static final double LUNCH_RADIUS_KM = 1.5;

    private final TravelTimeEstimator travel;

    public ItineraryPlanner(TravelTimeEstimator travel) {
        this.travel = travel;
    }

    /** {@code food}: restaurants to suggest for lunch (may be empty: lunch is still scheduled). */
    public record Request(GeoPoint start, LocalDate startDate, int days, TravelPace pace,
                          Set<String> interests, List<Place> candidates, List<Place> food) {

        public Request(GeoPoint start, LocalDate startDate, int days, TravelPace pace,
                       Set<String> interests, List<Place> candidates) {
            this(start, startDate, days, pace, interests, candidates, List.of());
        }
    }

    /** Daily time budget per pace, in minutes since midnight, plus lunch length. */
    record PaceProfile(int dayStart, int dayEnd, int maxStops, int lunchMinutes) {

        static PaceProfile of(TravelPace pace) {
            return switch (pace) {
                case RELAXED -> new PaceProfile(10 * 60, 17 * 60, 3, 75);
                case BALANCED -> new PaceProfile(9 * 60 + 30, 18 * 60 + 30, 5, 60);
                case PACKED -> new PaceProfile(8 * 60 + 30, 20 * 60 + 30, 7, 45);
            };
        }
    }

    public ItineraryPlan plan(Request request) {
        if (request.days() < 1) {
            throw new IllegalArgumentException("days must be >= 1");
        }
        PaceProfile pace = PaceProfile.of(request.pace());
        Comparator<Place> bestFirst = Comparator.<Place>comparingDouble(p -> -score(p, request.interests()))
                .thenComparing(Place::id);

        // 1-2. score, dedupe, keep the best pool
        Map<String, Place> unique = new LinkedHashMap<>();
        request.candidates().forEach(p -> unique.putIfAbsent(p.id(), p));
        List<Place> ranked = unique.values().stream().sorted(bestFirst).toList();
        int poolSize = (int) Math.ceil(request.days() * pace.maxStops() * POOL_FACTOR);
        List<Place> pool = ranked.subList(0, Math.min(poolSize, ranked.size()));

        // 3. one geographic cluster per day, strongest cluster first
        List<List<Place>> clusters = new ArrayList<>(GeoClusterer.kMeans(pool, Math.min(request.days(), pool.size())));
        clusters.sort(Comparator.comparingDouble(
                (List<Place> c) -> -c.stream().mapToDouble(p -> score(p, request.interests())).sum()));

        // 4. route + schedule each day
        List<DayScheduler> schedulers = new ArrayList<>();
        List<List<Place>> routes = new ArrayList<>();
        List<Place> leftovers = new ArrayList<>();
        for (int d = 0; d < request.days(); d++) {
            DayScheduler scheduler = new DayScheduler(travel, request.start(), pace,
                    request.startDate().plusDays(d).getDayOfWeek());
            List<Place> cluster = d < clusters.size() ? new ArrayList<>(clusters.get(d)) : new ArrayList<>();
            cluster.sort(bestFirst);
            schedulers.add(scheduler);
            routes.add(scheduler.build(cluster, leftovers));
        }

        // 5. spill-over: the day that absorbs each leftover with the smallest delay wins
        leftovers.sort(bestFirst);
        List<String> unscheduled = new ArrayList<>();
        for (Place place : leftovers) {
            int bestDay = -1;
            List<Place> bestRoute = null;
            int bestDelay = Integer.MAX_VALUE;
            for (int d = 0; d < routes.size(); d++) {
                if (routes.get(d).size() >= pace.maxStops()) {
                    continue;
                }
                DayScheduler scheduler = schedulers.get(d);
                List<Place> extended = scheduler.cheapestInsertion(routes.get(d), place);
                if (extended == null) {
                    continue;
                }
                int delay = scheduler.simulate(extended).finish() - scheduler.simulate(routes.get(d)).finish();
                if (delay < bestDelay) {
                    bestDay = d;
                    bestRoute = extended;
                    bestDelay = delay;
                }
            }
            if (bestDay < 0) {
                unscheduled.add(place.name());
            } else {
                routes.set(bestDay, schedulers.get(bestDay).improve(bestRoute));
            }
        }

        // 6. timetable + lunch spot per day
        List<ItineraryPlan.Day> days = new ArrayList<>();
        Set<String> usedForLunch = new HashSet<>();
        int total = 0;
        for (int d = 0; d < routes.size(); d++) {
            DayScheduler.Schedule schedule = schedulers.get(d).simulate(routes.get(d));
            ItineraryPlan.Day day = render(request, d, schedule, pace,
                    lunchSpot(request, schedule, pace, schedulers.get(d), usedForLunch));
            total += day.travelMinutes();
            days.add(day);
        }
        return new ItineraryPlan(ALGORITHM, days, unscheduled, total);
    }

    /** Best food place within walking distance of where you are at lunch, open for the whole break. */
    private static Place lunchSpot(Request request, DayScheduler.Schedule schedule, PaceProfile pace,
                                   DayScheduler scheduler, Set<String> used) {
        if (schedule.lunchStart() < 0) {
            return null;
        }
        GeoPoint where = schedule.lunchAfter() == 0 ? request.start()
                : schedule.visits().get(schedule.lunchAfter() - 1).place().point();
        int from = schedule.lunchStart();
        int to = from + pace.lunchMinutes();
        Place best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Place p : request.food()) {
            double km = where.distanceKm(p.point());
            if (used.contains(p.id()) || km > LUNCH_RADIUS_KM || !p.isOpenOn(scheduler.day())
                    || DayScheduler.minutes(p.opens()) > from || DayScheduler.minutes(p.closes()) < to) {
                continue;
            }
            double score = p.rating() - 0.5 * km;   // a 4.5 next door beats a 4.7 a kilometre away
            if (score > bestScore || (score == bestScore && p.id().compareTo(best.id()) < 0)) {
                best = p;
                bestScore = score;
            }
        }
        if (best != null) {
            used.add(best.id());
        }
        return best == null ? null : best.withDistanceFrom(where);
    }

    private ItineraryPlan.Day render(Request request, int dayIndex, DayScheduler.Schedule schedule, PaceProfile pace,
                                     Place lunchPlace) {
        List<DayScheduler.Visit> visits = schedule.visits();
        List<ItineraryPlan.Stop> stops = new ArrayList<>();
        int dayTravel = 0;
        for (int i = 0; i < visits.size(); i++) {
            DayScheduler.Visit v = visits.get(i);
            Integer toNext = i + 1 < visits.size() ? visits.get(i + 1).travelIn() : null;
            dayTravel += v.travelIn();
            Place p = v.place();
            stops.add(new ItineraryPlan.Stop(p.id(), p.name(), p.category(), p.lat(), p.lng(),
                    clock(v.arrive() + v.waitMinutes()), clock(v.leave()), toNext, v.waitMinutes() > 0 ? v.waitMinutes() : null));
        }
        ItineraryPlan.Lunch lunch = null;
        if (schedule.lunchStart() >= 0) {
            lunch = lunchPlace == null
                    ? new ItineraryPlan.Lunch(clock(schedule.lunchStart()), clock(schedule.lunchStart() + pace.lunchMinutes()),
                    schedule.lunchAfter(), null, null, null, null, null)
                    : new ItineraryPlan.Lunch(clock(schedule.lunchStart()), clock(schedule.lunchStart() + pace.lunchMinutes()),
                    schedule.lunchAfter(), lunchPlace.id(), lunchPlace.name(), lunchPlace.lat(), lunchPlace.lng(),
                    Math.round(lunchPlace.distanceKm() * 10) / 10.0);
        }
        return new ItineraryPlan.Day(request.startDate().plusDays(dayIndex).toString(), stops, dayTravel, lunch);
    }

    static double score(Place p, Set<String> interests) {
        double base = p.rating() / 5.0;
        return interests.contains(p.category()) ? base * INTEREST_BOOST : base;
    }

    private static String clock(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60).toString();
    }
}
