package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.Place;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds one day's route <em>with</em> its timetable, so opening hours shape the order instead of
 * being checked after it (design §11 v3, D52).
 *
 * <ul>
 *   <li>{@link #simulate}: walks a route from the day start: travel, wait for opening if early, visit,
 *       and a lunch break in the lunch window. Returns null if any visit is closed that weekday, would
 *       end after closing, or after the day ends.</li>
 *   <li>{@link #cheapestInsertion}: tries a place at every position of a route and keeps the feasible
 *       one that finishes the day earliest (then: least travel). Late openers therefore end up late
 *       in the day instead of causing idle waiting at the start.</li>
 *   <li>{@link #improve}: time-aware local search: 2-opt (reverse a segment) and relocate (move one
 *       stop), accepting a change only if the timetable stays feasible and the day finishes earlier
 *       or travels less.</li>
 * </ul>
 *
 * Lunch: it starts at the first gap at or after {@link #LUNCH_EARLIEST}, or at an earlier gap if the next
 * visit would otherwise run past {@link #LUNCH_LATEST}; so it always starts inside the window.
 */
final class DayScheduler {

    static final int LUNCH_EARLIEST = 12 * 60;
    static final int LUNCH_LATEST = 14 * 60;
    private static final int MAX_IMPROVEMENT_ROUNDS = 50;

    /** One timed visit. {@code waitMinutes}: minutes outside before it opened. */
    record Visit(Place place, int arrive, int leave, int waitMinutes, int travelIn) {
    }

    /**
     * A feasible day. {@code lunchStart} is -1 when there's no lunch (empty day); otherwise lunch
     * happens after the first {@code lunchAfter} visits. {@code finish}: when the last visit ends.
     */
    record Schedule(List<Visit> visits, int lunchStart, int lunchAfter, int finish, int travel) {

        boolean betterThan(Schedule other) {
            return finish < other.finish || (finish == other.finish && travel < other.travel);
        }
    }

    private final TravelTimeEstimator travel;
    private final GeoPoint start;
    private final ItineraryPlanner.PaceProfile pace;
    private final DayOfWeek day;

    DayScheduler(TravelTimeEstimator travel, GeoPoint start, ItineraryPlanner.PaceProfile pace, DayOfWeek day) {
        this.travel = travel;
        this.start = start;
        this.pace = pace;
        this.day = day;
    }

    DayOfWeek day() {
        return day;
    }

    /** The timetable for visiting {@code route} in order, or null if it doesn't fit. */
    Schedule simulate(List<Place> route) {
        List<Visit> visits = new ArrayList<>(route.size());
        int clock = pace.dayStart();
        GeoPoint position = start;
        int lunchStart = -1;
        int lunchAfter = -1;
        int travelTotal = 0;

        for (int i = 0; i < route.size(); i++) {
            Place place = route.get(i);
            if (!place.isOpenOn(day)) {
                return null;
            }
            if (lunchStart < 0 && clock >= LUNCH_EARLIEST) {
                lunchStart = clock;
                lunchAfter = i;
                clock += pace.lunchMinutes();
            }
            int legMinutes = travel.minutes(position, place.point());
            int opens = minutes(place.opens());
            int arrive = clock + legMinutes;
            int begin = Math.max(arrive, opens);
            int leave = begin + place.visitMinutes();
            if (lunchStart < 0 && leave > LUNCH_LATEST) {
                // Doing this visit first would push lunch past its window: eat now.
                lunchStart = Math.max(clock, LUNCH_EARLIEST);
                lunchAfter = i;
                clock = lunchStart + pace.lunchMinutes();
                arrive = clock + legMinutes;
                begin = Math.max(arrive, opens);
                leave = begin + place.visitMinutes();
            }
            if (leave > minutes(place.closes()) || leave > pace.dayEnd()) {
                return null;
            }
            visits.add(new Visit(place, arrive, leave, begin - arrive, legMinutes));
            clock = leave;
            position = place.point();
            travelTotal += legMinutes;
        }

        int finish = clock;
        if (lunchStart < 0 && !route.isEmpty() && clock <= LUNCH_LATEST) {
            // The sightseeing is over by lunchtime: lunch after the last stop.
            int at = Math.max(clock, LUNCH_EARLIEST);
            if (at + pace.lunchMinutes() <= pace.dayEnd()) {
                lunchStart = at;
                lunchAfter = route.size();
            }
        }
        return new Schedule(visits, lunchStart, lunchAfter, finish, travelTotal);
    }

    /** {@code route} with {@code place} inserted where the day finishes earliest, or null if no position fits. */
    List<Place> cheapestInsertion(List<Place> route, Place place) {
        List<Place> best = null;
        Schedule bestSchedule = null;
        for (int position = 0; position <= route.size(); position++) {
            List<Place> candidate = new ArrayList<>(route);
            candidate.add(position, place);
            Schedule schedule = simulate(candidate);
            if (schedule != null && (bestSchedule == null || schedule.betterThan(bestSchedule))) {
                best = candidate;
                bestSchedule = schedule;
            }
        }
        return best;
    }

    /**
     * Fills a day from {@code candidates} (best first): each is inserted at its cheapest feasible
     * position until {@code maxStops} is reached. Whatever doesn't fit goes to {@code rejected}.
     */
    List<Place> build(List<Place> candidates, List<Place> rejected) {
        List<Place> route = new ArrayList<>();
        for (Place place : candidates) {
            List<Place> extended = route.size() < pace.maxStops() ? cheapestInsertion(route, place) : null;
            if (extended == null) {
                rejected.add(place);
            } else {
                route = extended;
            }
        }
        return improve(route);
    }

    /** Time-aware 2-opt and relocate until neither helps. Never returns an infeasible route. */
    List<Place> improve(List<Place> route) {
        List<Place> best = new ArrayList<>(route);
        Schedule bestSchedule = simulate(best);
        if (bestSchedule == null || best.size() < 2) {
            return best;
        }
        int n = best.size();
        boolean improved = true;
        for (int round = 0; improved && round < MAX_IMPROVEMENT_ROUNDS; round++) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    List<Place> reversed = new ArrayList<>(best);
                    Collections.reverse(reversed.subList(i, j + 1));
                    Schedule s = simulate(reversed);
                    if (s != null && s.betterThan(bestSchedule)) {
                        best = reversed;
                        bestSchedule = s;
                        improved = true;
                    }
                }
            }
            for (int from = 0; from < n; from++) {
                for (int to = 0; to < n; to++) {
                    if (from == to) {
                        continue;
                    }
                    List<Place> moved = new ArrayList<>(best);
                    moved.add(to, moved.remove(from));
                    Schedule s = simulate(moved);
                    if (s != null && s.betterThan(bestSchedule)) {
                        best = moved;
                        bestSchedule = s;
                        improved = true;
                    }
                }
            }
        }
        return best;
    }

    static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
