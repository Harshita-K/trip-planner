package com.wanderly.catalog;

import com.wanderly.common.ApiException;
import com.wanderly.common.GeoPoint;
import com.wanderly.places.CityDirectory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    /** Keeps one account from flooding the catalogue; admins are exempt. */
    static final int MAX_UPCOMING_PER_USER = 20;
    private static final Duration MAX_AHEAD = Duration.ofDays(366);

    private final EventRepository events;
    private final TripRepository trips;
    private final CityDirectory cities;

    public CatalogService(EventRepository events, TripRepository trips, CityDirectory cities) {
        this.events = events;
        this.trips = trips;
        this.cities = cities;
    }

    public CatalogDtos.PageResponse<CatalogDtos.EventResponse> searchEvents(String city, String category,
                                                                          Instant from, int page, int size) {
        Specification<Event> spec = startsAfter(from == null ? Instant.now() : from);
        if (city != null && !city.isBlank()) {
            spec = spec.and(equalsIgnoreCase("city", city));
        }
        if (category != null && !category.isBlank()) {
            spec = spec.and(equalsIgnoreCase("category", category));
        }
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("startTime"));
        Page<Event> result = events.findAll(spec, pageRequest);
        return new CatalogDtos.PageResponse<>(result.map(CatalogDtos.EventResponse::from).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    public Event event(UUID id) {
        return events.findById(id).orElseThrow(() -> ApiException.notFound("Event"));
    }

    /** Events this user listed, newest date first (past ones included, so they can tidy up). */
    public List<Event> listedBy(UUID userId) {
        return events.findByCreatedByOrderByStartTimeDesc(userId);
    }

    @Transactional
    public Event create(UUID userId, boolean admin, CatalogDtos.EventRequest request) {
        validateStart(request.startTime());
        if (!admin && events.countByCreatedByAndStartTimeAfter(userId, Instant.now()) >= MAX_UPCOMING_PER_USER) {
            throw ApiException.conflict("You already have " + MAX_UPCOMING_PER_USER
                    + " upcoming events listed. Remove one before adding another.");
        }
        GeoPoint where = venuePoint(request);
        return events.save(new Event(userId, request.title().trim(), request.category(), request.venue().trim(),
                request.city().trim(), where.lat(), where.lng(), request.startTime(), price(request),
                blankToNull(request.description())));
    }

    @Transactional
    public Event update(UUID userId, boolean admin, UUID id, CatalogDtos.EventRequest request) {
        Event event = editable(userId, admin, id);
        validateStart(request.startTime());
        boolean moved = !event.getVenue().equalsIgnoreCase(request.venue().trim())
                || !event.getCity().equalsIgnoreCase(request.city().trim());
        GeoPoint where = moved ? venuePoint(request) : new GeoPoint(event.getLat(), event.getLng());
        event.update(request.title().trim(), request.category(), request.venue().trim(), request.city().trim(),
                where.lat(), where.lng(), request.startTime(), price(request), blankToNull(request.description()));
        return event;
    }

    /** Saves of the event go with it (FK cascade). */
    @Transactional
    public void delete(UUID userId, boolean admin, UUID id) {
        events.delete(editable(userId, admin, id));
    }

    public List<CatalogDtos.TripResponse> trips() {
        return trips.findAll(Sort.by("destination")).stream().map(CatalogDtos.TripResponse::from).toList();
    }

    public Trip trip(UUID id) {
        return trips.findById(id).orElseThrow(() -> ApiException.notFound("Trip"));
    }

    /** Only whoever listed an event (or an admin) may change it; seeded events are admin-only. */
    private Event editable(UUID userId, boolean admin, UUID id) {
        Event event = event(id);
        if (!admin && !userId.equals(event.getCreatedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only change events you listed.");
        }
        return event;
    }

    private static void validateStart(Instant start) {
        Instant now = Instant.now();
        if (start.isBefore(now)) {
            throw ApiException.badRequest("The event must start in the future.");
        }
        if (start.isAfter(now.plus(MAX_AHEAD))) {
            throw ApiException.badRequest("Events can be listed up to a year ahead.");
        }
    }

    /** The venue's own position when it can be found near the city, else the city centre. */
    private GeoPoint venuePoint(CatalogDtos.EventRequest request) {
        GeoPoint city = new GeoPoint(request.lat(), request.lng());
        return cities.locate(request.venue().trim(), request.city().trim(), city).orElse(city);
    }

    private static BigDecimal price(CatalogDtos.EventRequest request) {
        return request.price() == null ? BigDecimal.ZERO : request.price();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Specification<Event> startsAfter(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startTime"), from);
    }

    private static Specification<Event> equalsIgnoreCase(String field, String value) {
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get(field)), normalised);
    }
}
