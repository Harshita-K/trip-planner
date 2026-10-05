package com.wanderly.itinerary;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ItineraryDtos {

    private ItineraryDtos() {
    }

    /**
     * {@code lat/lng} is the destination centre; {@code hotelLat/hotelLng}, if given, is where
     * each day starts. {@code travelPace} and {@code interests} default to the user's preferences.
     */
    public record GenerateRequest(
            @NotBlank String destination,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @DecimalMin("-90") @DecimalMax("90") Double hotelLat,
            @DecimalMin("-180") @DecimalMax("180") Double hotelLng,
            @DecimalMin("1") @DecimalMax("50") Double radiusKm,
            @Pattern(regexp = "relaxed|balanced|packed") String travelPace,
            List<String> interests) {
    }

    public record ItineraryResponse(UUID id, String destination, LocalDate startDate, LocalDate endDate,
                                    ItineraryPlan plan, Instant createdAt) {

        public static ItineraryResponse from(Itinerary i) {
            return new ItineraryResponse(i.getId(), i.getDestination(), i.getStartDate(), i.getEndDate(),
                    i.getPlan(), i.getCreatedAt());
        }
    }
}
