package com.wanderly.catalog;

import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.recommendation.NearbySuggestionService;
import com.wanderly.user.AdminPolicy;
import com.wanderly.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;
    private final EventPublisher publisher;
    private final NearbySuggestionService nearby;
    private final AdminPolicy admins;

    public CatalogController(CatalogService catalog, EventPublisher publisher, NearbySuggestionService nearby,
                             AdminPolicy admins) {
        this.catalog = catalog;
        this.publisher = publisher;
        this.nearby = nearby;
        this.admins = admins;
    }

    @GetMapping("/events")
    public CatalogDtos.PageResponse<CatalogDtos.EventResponse> searchEvents(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.searchEvents(city, category, from, page, size);
    }

    /** Events the caller listed. (Under the public GET matcher, but a token is required.) */
    @GetMapping("/events/mine")
    public List<CatalogDtos.EventResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return catalog.listedBy(CurrentUser.id(jwt)).stream().map(CatalogDtos.EventResponse::from).toList();
    }

    @GetMapping("/events/{id}")
    public CatalogDtos.EventResponse event(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Event event = catalog.event(id);
        UUID userId = CurrentUser.idOrNull(jwt);
        if (userId != null) {
            publisher.activity(userId, ActivityEvent.EVENT_VIEWED, "event", id.toString(),
                    event.getCategory(), event.getCity());
        }
        return CatalogDtos.EventResponse.from(event);
    }

    /** F7: food and sights around the venue; personalised when a token is sent. */
    @GetMapping("/events/{id}/nearby")
    public NearbySuggestionService.NearbySuggestions nearby(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Event event = catalog.event(id);
        return nearby.around(CurrentUser.idOrNull(jwt), event.getLat(), event.getLng());
    }

    /** List an event in any city: organisers fill the gaps the seeded catalogue leaves. */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogDtos.EventResponse create(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody CatalogDtos.EventRequest request) {
        return CatalogDtos.EventResponse.from(catalog.create(CurrentUser.id(jwt), admins.isAdmin(jwt), request));
    }

    @PutMapping("/events/{id}")
    public CatalogDtos.EventResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody CatalogDtos.EventRequest request) {
        return CatalogDtos.EventResponse.from(catalog.update(CurrentUser.id(jwt), admins.isAdmin(jwt), id, request));
    }

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.delete(CurrentUser.id(jwt), admins.isAdmin(jwt), id);
    }

    @GetMapping("/trips")
    public List<CatalogDtos.TripResponse> trips() {
        return catalog.trips();
    }

    @GetMapping("/trips/{id}")
    public CatalogDtos.TripResponse trip(@PathVariable UUID id) {
        return CatalogDtos.TripResponse.from(catalog.trip(id));
    }
}
