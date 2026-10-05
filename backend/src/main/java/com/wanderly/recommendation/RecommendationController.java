package com.wanderly.recommendation;

import com.wanderly.user.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/feed")
    public RecommendationService.Feed feed(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(required = false) String city,
                                           @RequestParam(defaultValue = "10") int limit) {
        return recommendations.feed(CurrentUser.id(jwt), city, Math.clamp(limit, 1, 50));
    }
}
