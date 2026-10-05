package com.wanderly.saved;

import com.wanderly.catalog.CatalogDtos;
import com.wanderly.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The user's saved events, addressed by event id. PUT and DELETE are idempotent. */
@RestController
@RequestMapping("/api/saved-events")
public class SavedEventController {

    private final SavedEventService saved;

    public SavedEventController(SavedEventService saved) {
        this.saved = saved;
    }

    @GetMapping
    public List<SavedEventResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return saved.list(CurrentUser.id(jwt)).stream().map(SavedEventResponse::from).toList();
    }

    @PutMapping("/{eventId}")
    public SavedEventResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return SavedEventResponse.from(saved.save(CurrentUser.id(jwt), eventId));
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        saved.unsave(CurrentUser.id(jwt), eventId);
    }

    public record SavedEventResponse(CatalogDtos.EventResponse event, Instant savedAt) {

        static SavedEventResponse from(SavedEventService.Saved s) {
            return new SavedEventResponse(CatalogDtos.EventResponse.from(s.event()), s.save().getCreatedAt());
        }
    }
}
