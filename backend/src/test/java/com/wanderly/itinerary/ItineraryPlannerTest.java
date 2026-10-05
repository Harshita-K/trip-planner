package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.Place;
import com.wanderly.user.TravelPace;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.wanderly.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class ItineraryPlannerTest {

    private static final GeoPoint HOTEL = new GeoPoint(12.97, 77.59);
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 10, 1);

    private final ItineraryPlanner planner = new ItineraryPlanner(new HaversineTravelTimeEstimator());

    @Test
    void keepsEachDayInOneArea() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(place("city-" + i, "museum", 12.97 + i * 0.005, 77.59 + i * 0.003, 4.5));
            candidates.add(place("outskirts-" + i, "nature", 12.80 + i * 0.005, 77.40 + i * 0.003, 4.4));
        }

        ItineraryPlan plan = plan(2, TravelPace.BALANCED, Set.of(), candidates);

        assertThat(plan.days()).hasSize(2);
        assertThat(plan.unscheduled()).isEmpty();
        for (ItineraryPlan.Day day : plan.days()) {
            assertThat(day.stops()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
            String area = day.stops().get(0).placeId().split("-")[0];
            assertThat(day.stops()).allMatch(s -> s.placeId().startsWith(area));
        }
        assertThat(plan.days()).extracting(ItineraryPlan.Day::date)
                .containsExactly("2026-10-01", "2026-10-02");
    }

    @Test
    void scheduleIsChronologicalAndAccountsForTravel() {
        List<Place> candidates = List.of(
                place("a", "museum", 12.975, 77.595, 4.6),
                place("b", "museum", 12.985, 77.600, 4.5),
                place("c", "history", 12.960, 77.580, 4.4),
                place("d", "nature", 12.950, 77.585, 4.3));

        ItineraryPlan.Day day = plan(1, TravelPace.BALANCED, Set.of(), candidates).days().get(0);

        for (int i = 0; i + 1 < day.stops().size(); i++) {
            ItineraryPlan.Stop current = day.stops().get(i);
            ItineraryPlan.Stop next = day.stops().get(i + 1);
            LocalTime earliestNext = LocalTime.parse(current.leave()).plusMinutes(current.travelToNextMin());
            assertThat(LocalTime.parse(next.arrive())).isAfterOrEqualTo(earliestNext);
        }
        assertThat(day.stops().get(day.stops().size() - 1).travelToNextMin()).isNull();
    }

    @Test
    void respectsOpeningHours() {
        List<Place> candidates = List.of(
                place("afternoon-only", "museum", 12.971, 77.591, 4.8, 60, "14:00", "17:00"),
                place("night-market", "shopping", 12.972, 77.592, 4.9, 60, "20:00", "23:00"),
                place("all-day", "nature", 12.973, 77.593, 4.0, 60, "06:00", "20:00"));

        ItineraryPlan plan = plan(1, TravelPace.BALANCED, Set.of(), candidates);
        Map<String, ItineraryPlan.Stop> stops = plan.days().get(0).stops().stream()
                .collect(Collectors.toMap(ItineraryPlan.Stop::placeId, Function.identity()));

        ItineraryPlan.Stop afternoon = stops.get("afternoon-only");
        assertThat(afternoon).isNotNull();
        assertThat(LocalTime.parse(afternoon.arrive())).isAfterOrEqualTo(LocalTime.of(14, 0));
        assertThat(LocalTime.parse(afternoon.leave())).isBeforeOrEqualTo(LocalTime.of(17, 0));
        // Opens after a balanced day ends (18:30): cannot be scheduled.
        assertThat(stops).doesNotContainKey("night-market");
        assertThat(plan.unscheduled()).containsExactly("night-market");
    }

    @Test
    void prefersTheUsersInterests() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            candidates.add(place("museum-" + i, "museum", 12.970 + i * 0.002, 77.590, 4.0));
        }
        for (int i = 0; i < 4; i++) {
            candidates.add(place("park-" + i, "nature", 12.971 + i * 0.002, 77.591, 4.8));
        }

        ItineraryPlan plan = plan(1, TravelPace.RELAXED, Set.of("museum"), candidates);

        assertThat(plan.days().get(0).stops())
                .hasSize(3)
                .allMatch(s -> s.category().equals("museum"));
    }

    @Test
    void paceControlsHowManyStopsFitInADay() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            candidates.add(place("p" + i, "museum", 12.970 + i * 0.001, 77.590, 4.5, 45, "06:00", "22:00"));
        }

        int relaxed = plan(1, TravelPace.RELAXED, Set.of(), candidates).days().get(0).stops().size();
        int packed = plan(1, TravelPace.PACKED, Set.of(), candidates).days().get(0).stops().size();

        assertThat(relaxed).isEqualTo(3);
        assertThat(packed).isEqualTo(7);
    }

    @Test
    void neverSchedulesAPlaceTwice() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(place("p" + i, i % 2 == 0 ? "museum" : "nature",
                    12.90 + (i % 5) * 0.02, 77.55 + (i / 5) * 0.02, 3.5 + (i % 4) * 0.3));
        }
        candidates.add(candidates.get(0)); // duplicate input

        ItineraryPlan plan = plan(3, TravelPace.PACKED, Set.of("museum"), candidates);

        List<String> ids = plan.days().stream().flatMap(d -> d.stops().stream()).map(ItineraryPlan.Stop::placeId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void moreDaysThanPlacesStillProducesAPlan() {
        List<Place> candidates = List.of(place("a", "museum", 12.97, 77.59, 4.5), place("b", "museum", 12.98, 77.60, 4.5));

        ItineraryPlan plan = plan(3, TravelPace.BALANCED, Set.of(), candidates);

        assertThat(plan.days()).hasSize(3);
        assertThat(plan.days().stream().mapToInt(d -> d.stops().size()).sum()).isEqualTo(2);
    }

    @Test
    void neverVisitsAPlaceOnItsClosedDay() {
        // 2026-10-05 is a Monday. The museum is the best stop but closed on Mondays.
        LocalDate monday = LocalDate.of(2026, 10, 5);
        Place museum = place("museum", "museum", 12.971, 77.591, 4.9, 60, "10:00", "17:00")
                .withHours(LocalTime.of(10, 0), LocalTime.of(17, 0), Set.of(DayOfWeek.MONDAY), Place.OSM);
        List<Place> candidates = List.of(museum, place("park", "nature", 12.972, 77.592, 4.0));

        ItineraryPlan plan = planner.plan(new ItineraryPlanner.Request(HOTEL, monday, 2, TravelPace.BALANCED, Set.of(), candidates));

        assertThat(plan.days().get(0).stops()).extracting(ItineraryPlan.Stop::placeId).doesNotContain("museum");
        assertThat(plan.days().get(1).stops()).extracting(ItineraryPlan.Stop::placeId).contains("museum");
    }

    @Test
    void isDeterministic() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candidates.add(place("p" + i, "museum", 12.90 + (i % 5) * 0.03, 77.50 + (i / 5) * 0.03, 4.0 + (i % 3) * 0.2));
        }
        assertThat(plan(2, TravelPace.BALANCED, Set.of(), candidates))
                .isEqualTo(plan(2, TravelPace.BALANCED, Set.of(), candidates));
    }

    @Test
    void lateOpenerGoesLaterInsteadOfCausingIdleWaiting() {
        // Regression for the build-notes plan that started at 12:00 waiting for a pub to open:
        // the late opener is the best-rated and the closest to the hotel, so ordering by distance put it first.
        List<Place> candidates = List.of(
                place("pub", "nightlife", 12.9705, 77.5905, 4.9, 90, "12:00", "23:00"),
                place("palace", "history", 12.980, 77.600, 4.5, 90, "09:00", "18:00"),
                place("park", "nature", 12.985, 77.595, 4.4, 60, "06:00", "19:00"));

        ItineraryPlan.Day day = plan(1, TravelPace.BALANCED, Set.of(), candidates).days().get(0);

        assertThat(day.stops()).extracting(ItineraryPlan.Stop::placeId).containsExactlyInAnyOrder("pub", "palace", "park");
        assertThat(day.stops().get(0).placeId()).isNotEqualTo("pub");
        assertThat(day.stops()).allMatch(s -> s.waitMin() == null);
        assertThat(LocalTime.parse(day.stops().get(0).arrive())).isBefore(LocalTime.of(10, 0));
    }

    @Test
    void fillsTheDayWhenTheTopPicksDoNotFit() {
        // The two best places only open after a relaxed day ends; the next three should take their slots.
        List<Place> candidates = List.of(
                place("night-1", "nightlife", 12.971, 77.591, 5.0, 60, "19:00", "23:00"),
                place("night-2", "nightlife", 12.972, 77.592, 4.9, 60, "19:00", "23:00"),
                place("a", "museum", 12.973, 77.593, 4.0),
                place("b", "museum", 12.974, 77.594, 4.0),
                place("c", "museum", 12.975, 77.595, 4.0));

        ItineraryPlan plan = plan(1, TravelPace.RELAXED, Set.of(), candidates);

        assertThat(plan.days().get(0).stops()).extracting(ItineraryPlan.Stop::placeId).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(plan.unscheduled()).containsExactlyInAnyOrder("night-1", "night-2");
    }

    @Test
    void lunchIsInTheWindowAndBetweenStops() {
        List<Place> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(place("p" + i, "museum", 12.970 + i * 0.004, 77.590 + (i % 3) * 0.004, 4.5, 75, "08:00", "20:00"));
        }

        for (TravelPace pace : TravelPace.values()) {
            ItineraryPlan.Day day = plan(1, pace, Set.of(), candidates).days().get(0);
            ItineraryPlan.Lunch lunch = day.lunch();
            assertThat(lunch).as(pace + " has lunch").isNotNull();
            LocalTime start = LocalTime.parse(lunch.start());
            assertThat(start).isBetween(LocalTime.of(12, 0), LocalTime.of(14, 0));
            assertThat(lunch.afterStops()).isBetween(0, day.stops().size());
            if (lunch.afterStops() > 0) {
                assertThat(LocalTime.parse(day.stops().get(lunch.afterStops() - 1).leave())).isBeforeOrEqualTo(start);
            }
            if (lunch.afterStops() < day.stops().size()) {
                assertThat(LocalTime.parse(day.stops().get(lunch.afterStops()).arrive()))
                        .isAfterOrEqualTo(LocalTime.parse(lunch.end()));
            }
        }
    }

    @Test
    void lunchSuggestsANearbyRestaurantThatIsOpenAndNotRepeated() {
        List<Place> sights = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sights.add(place("s" + i, "museum", 12.970 + (i % 5) * 0.002, 77.590 + (i / 5) * 0.002, 4.5, 90, "08:00", "20:00"));
        }
        List<Place> food = List.of(
                place("dinner-only", "food", 12.971, 77.591, 5.0, 60, "19:00", "23:00"),
                place("far-away", "food", 13.100, 77.700, 5.0, 60, "11:00", "23:00"),
                place("cafe", "food", 12.972, 77.591, 4.3, 60, "08:00", "22:00"),
                place("thali", "food", 12.973, 77.592, 4.6, 60, "11:00", "16:00"));

        ItineraryPlan plan = planner.plan(new ItineraryPlanner.Request(HOTEL, DAY_ONE, 2, TravelPace.BALANCED, Set.of(),
                sights, food));

        List<String> lunchSpots = plan.days().stream().map(d -> d.lunch().placeId()).toList();
        assertThat(lunchSpots).containsExactly("thali", "cafe");   // best first, then no repeat
        assertThat(plan.days().get(0).lunch().distanceKm()).isLessThanOrEqualTo(ItineraryPlanner.LUNCH_RADIUS_KM);
        assertThat(plan.days()).allSatisfy(d -> d.stops().forEach(s -> assertThat(s.category()).isNotEqualTo("food")));
    }

    @Test
    void reportsWaitingWhenNothingElseCanFillTheGap() {
        List<Place> candidates = List.of(place("afternoon", "museum", 12.971, 77.591, 4.8, 60, "15:00", "17:00"));

        ItineraryPlan.Stop stop = plan(1, TravelPace.BALANCED, Set.of(), candidates).days().get(0).stops().get(0);

        assertThat(stop.arrive()).isEqualTo("15:00");
        assertThat(stop.waitMin()).isPositive();
    }

    private ItineraryPlan plan(int days, TravelPace pace, Set<String> interests, List<Place> candidates) {
        return planner.plan(new ItineraryPlanner.Request(HOTEL, DAY_ONE, days, pace, interests, candidates));
    }
}
