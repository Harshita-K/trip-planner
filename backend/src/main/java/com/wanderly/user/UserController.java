package com.wanderly.user;

import com.wanderly.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserRepository users;
    private final UserPreferencesRepository preferences;

    public UserController(UserRepository users, UserPreferencesRepository preferences) {
        this.users = users;
        this.preferences = preferences;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public AuthDtos.ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return profile(CurrentUser.id(jwt));
    }

    @PutMapping("/preferences")
    @Transactional
    public AuthDtos.ProfileResponse updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody AuthDtos.PreferencesRequest request) {
        UUID userId = CurrentUser.id(jwt);
        UserPreferences prefs = preferences.findById(userId).orElseGet(() -> new UserPreferences(userId));
        List<String> interests = request.interests() == null
                ? prefs.getInterests()
                : List.copyOf(PreferencesService.normalise(request.interests()));
        String budget = request.budgetLevel() == null ? prefs.getBudgetLevel() : request.budgetLevel();
        TravelPace pace = request.travelPace() == null ? prefs.getTravelPace() : TravelPace.from(request.travelPace());
        prefs.update(interests, budget, pace);
        preferences.save(prefs);
        return profile(userId);
    }

    private AuthDtos.ProfileResponse profile(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        UserPreferences prefs = preferences.findById(userId).orElseGet(() -> new UserPreferences(userId));
        return new AuthDtos.ProfileResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt(),
                List.copyOf(prefs.getInterests()), prefs.getBudgetLevel(), prefs.getTravelPace());
    }
}
