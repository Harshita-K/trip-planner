package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.Place;
import com.wanderly.user.TravelPace;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.wanderly.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

class DaySchedulerTest {

    private static final GeoPoint START = new GeoPoint(12.90, 77.50);
    private final DayScheduler balanced = scheduler(TravelPace.BALANCED, DayOfWeek.THURSDAY);

    @Test
    void untanglesCrossingLegs() {
        // Square corners: start -> A -> C -> B crosses the diagonal.
        Place a = place("A", "museum", 12.90, 77.51, 4.0);
        Place b = place("B", "museum", 12.91, 77.51, 4.0);
        Place c = place("C", "museum", 12.91, 77.50, 4.0);
        List<Place> crossing = List.of(a, c, b);

        List<Place> improved = balanced.improve(crossing);

        assertThat(balanced.simulate(improved).travel()).isLessThan(balanced.simulate(crossing).travel());
        assertThat(improved).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void improvingNeverMakesADayWorseOrInfeasible() {
        Random random = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            List<Place> places = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int opens = 8 + random.nextInt(6);
                places.add(place("p" + i, "museum", 12.9 + random.nextDouble() * 0.05, 77.5 + random.nextDouble() * 0.05,
                        4.0, 45, "%02d:00".formatted(opens), "19:00"));
            }
            List<Place> built = balanced.build(places, new ArrayList<>());
            DayScheduler.Schedule before = balanced.simulate(built);
            DayScheduler.Schedule after = balanced.simulate(balanced.improve(built));

            assertThat(before).isNotNull();
            assertThat(after).isNotNull();
            assertThat(after.finish()).isLessThanOrEqualTo(before.finish());
        }
    }

    @Test
    void rejectsVisitsThatEndAfterClosingOrOnClosedDays() {
        Place shortWindow = place("short", "museum", 12.901, 77.501, 4.0, 120, "10:00", "11:00");
        Place closedThursday = place("closed", "museum", 12.901, 77.501, 4.0)
                .withHours(LocalTime.of(8, 0), LocalTime.of(20, 0), Set.of(DayOfWeek.THURSDAY), Place.OSM);

        assertThat(balanced.simulate(List.of(shortWindow))).isNull();
        assertThat(balanced.simulate(List.of(closedThursday))).isNull();
        assertThat(scheduler(TravelPace.BALANCED, DayOfWeek.FRIDAY).simulate(List.of(closedThursday))).isNotNull();
    }

    @Test
    void eatsBeforeAVisitThatWouldRunPastTheLunchWindow() {
        // 11:30 -> a 3-hour visit would end 14:30, so lunch comes first, starting at 12:00.
        Place morning = place("morning", "museum", 12.901, 77.501, 4.0, 120, "09:30", "20:00");
        Place long3h = place("long", "amusement", 12.902, 77.502, 4.0, 180, "08:00", "20:00");

        DayScheduler.Schedule s = balanced.simulate(List.of(morning, long3h));

        assertThat(s.lunchAfter()).isEqualTo(1);
        assertThat(s.lunchStart()).isEqualTo(DayScheduler.LUNCH_EARLIEST);
        assertThat(s.visits().get(1).arrive()).isGreaterThanOrEqualTo(DayScheduler.LUNCH_EARLIEST + 60);
    }

    private static DayScheduler scheduler(TravelPace pace, DayOfWeek day) {
        return new DayScheduler(new HaversineTravelTimeEstimator(), START, ItineraryPlanner.PaceProfile.of(pace), day);
    }
}
