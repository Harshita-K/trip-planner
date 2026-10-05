package com.wanderly.itinerary;

import com.wanderly.user.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/itineraries")
public class ItineraryController {

    private final ItineraryService itineraries;

    public ItineraryController(ItineraryService itineraries) {
        this.itineraries = itineraries;
    }

    /** F6: generate and save a day-by-day plan. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItineraryDtos.ItineraryResponse generate(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody ItineraryDtos.GenerateRequest request) {
        return ItineraryDtos.ItineraryResponse.from(itineraries.generate(CurrentUser.id(jwt), request));
    }

    /** Try the planner without an account: same plan, nothing saved. */
    @PostMapping("/preview")
    public ItineraryDtos.ItineraryResponse preview(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody ItineraryDtos.GenerateRequest request) {
        ItineraryPlan plan = itineraries.plan(CurrentUser.idOrNull(jwt), request);
        return new ItineraryDtos.ItineraryResponse(null, request.destination().trim(), request.startDate(),
                request.endDate(), plan, null);
    }

    @GetMapping
    public List<ItineraryDtos.ItineraryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return itineraries.list(CurrentUser.id(jwt)).stream().map(ItineraryDtos.ItineraryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ItineraryDtos.ItineraryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ItineraryDtos.ItineraryResponse.from(itineraries.get(CurrentUser.id(jwt), id));
    }
}
