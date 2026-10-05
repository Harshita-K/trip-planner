package com.wanderly.travel;

import com.wanderly.common.GeoPoint;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.user.CurrentUser;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private final TravelService travel;
    private final EventPublisher publisher;

    public TravelController(TravelService travel, EventPublisher publisher) {
        this.travel = travel;
        this.publisher = publisher;
    }

    /** F8: flight, train, bus and car options between two places, with door-to-door time and indicative fares. */
    @GetMapping
    public TravelDtos.TravelPlan options(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam @NotBlank String fromName,
                                         @RequestParam @DecimalMin("-90") @DecimalMax("90") double fromLat,
                                         @RequestParam @DecimalMin("-180") @DecimalMax("180") double fromLng,
                                         @RequestParam @NotBlank String toName,
                                         @RequestParam @DecimalMin("-90") @DecimalMax("90") double toLat,
                                         @RequestParam @DecimalMin("-180") @DecimalMax("180") double toLng,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(defaultValue = "1") @Min(1) @Max(9) int travellers) {
        TravelDtos.TravelPlan plan = travel.plan(fromName.trim(), new GeoPoint(fromLat, fromLng), toName.trim(),
                new GeoPoint(toLat, toLng), date, travellers);
        UUID userId = CurrentUser.idOrNull(jwt);
        if (userId != null) {
            publisher.activity(userId, ActivityEvent.TRAVEL_SEARCHED, "route", fromName.trim() + " → " + toName.trim(), null, toName.trim());
        }
        return plan;
    }
}
