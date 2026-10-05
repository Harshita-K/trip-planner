package com.wanderly.travel;

import java.util.List;

public final class TravelDtos {

    private TravelDtos() {
    }

    public record Fare(String travelClass, int price) {
    }

    public record Departure(String label, String depart, String arrive, int durationMinutes, List<Fare> fares) {
    }

    /**
     * One way of getting there. Prices are per person (except car: per vehicle, see {@code notes});
     * {@code total} covers all travellers. {@code doorToDoorMinutes} includes getting to and from
     * airports/stations and check-in buffers, which is what makes options comparable.
     */
    public record Option(String mode, String title, String route, double distanceKm, int doorToDoorMinutes,
                         int fromPrice, int total, double co2KgPerPerson, List<String> badges, List<String> notes,
                         List<Departure> departures) {
    }

    public record TravelPlan(String from, String to, String date, int travellers, List<Option> options,
                             boolean roadDistanceEstimated, String disclaimer) {
    }
}
