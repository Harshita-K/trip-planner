package com.wanderly.notification;

import com.wanderly.itinerary.Itinerary;
import com.wanderly.itinerary.ItineraryPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderSchedulerTest {

    @Test
    void tripReminderListsDayOneWithLunchInPlace() {
        ItineraryPlan.Day dayOne = new ItineraryPlan.Day("2026-10-10", List.of(
                stop("Bangalore Palace", "09:40", "11:10"),
                stop("Cubbon Park", "11:20", "12:20"),
                stop("Lalbagh", "13:40", "14:40")), 30,
                new ItineraryPlan.Lunch("12:20", "13:20", 2, "osm-1", "MTR", 12.95, 77.58, 0.4));
        ItineraryPlan.Day dayTwo = new ItineraryPlan.Day("2026-10-11", List.of(stop("ISKCON", "10:00", "10:45")), 10, null);
        Itinerary trip = new Itinerary(UUID.randomUUID(), "Bengaluru", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11),
                new ItineraryPlan("test", List.of(dayOne, dayTwo), List.of(), 40));

        String body = ReminderScheduler.tripBody(trip);

        assertThat(body).startsWith("Your trip to Bengaluru starts tomorrow (Sat, 10 Oct). Here's day 1:");
        assertThat(body).containsSubsequence("09:40–11:10  Bangalore Palace", "11:20–12:20  Cubbon Park",
                "12:20–13:20  Lunch at MTR", "13:40–14:40  Lalbagh");
        assertThat(body).contains("1 more day planned.");
    }

    @Test
    void tripReminderWithoutStopsStillMakesSense() {
        Itinerary trip = new Itinerary(UUID.randomUUID(), "Ziro", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10),
                new ItineraryPlan("test", List.of(new ItineraryPlan.Day("2026-10-10", List.of(), 0, null)), List.of(), 0));

        assertThat(ReminderScheduler.tripBody(trip)).isEqualTo(
                "Your trip to Ziro starts tomorrow (Sat, 10 Oct).\nOpen Wanderly to see your plan.");
    }

    private static ItineraryPlan.Stop stop(String name, String arrive, String leave) {
        return new ItineraryPlan.Stop(name, name, "history", 12.97, 77.59, arrive, leave, null, null);
    }
}
