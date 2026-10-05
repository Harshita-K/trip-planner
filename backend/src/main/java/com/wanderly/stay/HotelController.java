package com.wanderly.stay;

import com.wanderly.common.GeoPoint;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.user.CurrentUser;
import com.wanderly.user.PreferencesService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/hotels")
public class HotelController {

    private final HotelService hotels;
    private final PreferencesService preferences;
    private final EventPublisher publisher;

    public HotelController(HotelService hotels, PreferencesService preferences, EventPublisher publisher) {
        this.hotels = hotels;
        this.preferences = preferences;
        this.publisher = publisher;
    }

    /** F9: places to stay near a destination. {@code budget} (low|mid|high) defaults to the user's preference. */
    @GetMapping
    public HotelDtos.Stay near(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "") String name,
                               @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                               @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                               @RequestParam(defaultValue = "2") @Min(1) @Max(12) int guests,
                               @RequestParam(required = false) String budget) {
        UUID userId = CurrentUser.idOrNull(jwt);
        String effective = HotelService.normaliseBudget(budget != null ? budget : preferences.forUser(userId).budgetLevel());
        HotelDtos.Stay stay = hotels.near(name.trim(), new GeoPoint(lat, lng), checkIn, checkOut, guests, effective);
        if (userId != null) {
            publisher.activity(userId, ActivityEvent.HOTELS_SEARCHED, "stay", name.trim(), effective, name.trim());
        }
        return stay;
    }
}
