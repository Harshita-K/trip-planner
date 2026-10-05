package com.wanderly.catalog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogDtos {

    /** Event categories; they double as interests, so they line up with the frontend's list. */
    public static final String EVENT_CATEGORIES = "music|comedy|history|food|nature|business|books|art|sports";

    private CatalogDtos() {
    }

    /** {@code community}: listed by a user rather than part of the seeded catalogue. */
    public record EventResponse(UUID id, String title, String category, String venue, String city,
                                double lat, double lng, Instant startTime, BigDecimal price, String currency,
                                String description, boolean community) {

        public static EventResponse from(Event e) {
            return new EventResponse(e.getId(), e.getTitle(), e.getCategory(), e.getVenue(), e.getCity(),
                    e.getLat(), e.getLng(), e.getStartTime(), e.getPrice(), e.getCurrency(), e.getDescription(),
                    e.getCreatedBy() != null);
        }
    }

    /**
     * List (or edit) an event. {@code lat/lng} is the city's centre from the city picker; the venue is
     * geocoded near it when possible. {@code price} is the entry fee for planning (0 or absent = free).
     */
    public record EventRequest(
            @NotBlank @Size(max = 120) String title,
            @NotNull @Pattern(regexp = EVENT_CATEGORIES) String category,
            @NotBlank @Size(max = 160) String venue,
            @NotBlank @Size(max = 80) String city,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotNull Instant startTime,
            @DecimalMin("0") @DecimalMax("1000000") BigDecimal price,
            @Size(max = 2000) String description) {
    }

    /** A trip idea: where and for how long. Opening one starts the planner with these values. */
    public record TripResponse(UUID id, String destination, double lat, double lng, int durationDays, String description) {

        public static TripResponse from(Trip t) {
            return new TripResponse(t.getId(), t.getDestination(), t.getLat(), t.getLng(), t.getDurationDays(),
                    t.getDescription());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
