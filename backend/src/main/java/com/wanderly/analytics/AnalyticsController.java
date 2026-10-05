package com.wanderly.analytics;

import com.wanderly.common.ApiException;
import com.wanderly.user.AdminPolicy;
import com.wanderly.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** F12 dashboard data. Admin-only: {@code ADMINS} emails, or any logged-in user when that's empty (dev). */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Duration CACHE = Duration.ofSeconds(20);

    private final AnalyticsQueries queries;
    private final AdminPolicy admins;
    private volatile AnalyticsQueries.Summary cached;
    private volatile long cachedAt;

    public AnalyticsController(AnalyticsQueries queries, AdminPolicy admins) {
        this.queries = queries;
        this.admins = admins;
    }

    @GetMapping("/summary")
    public AnalyticsQueries.Summary summary(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser.id(jwt);   // 401 if anonymous
        if (!admins.noneConfigured() && !admins.isAdmin(jwt)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Analytics are only available to admins.");
        }
        if (cached == null || System.currentTimeMillis() - cachedAt > CACHE.toMillis()) {
            cached = queries.summary();   // DuckDB over the lake; cached briefly so refreshes stay cheap
            cachedAt = System.currentTimeMillis();
        }
        return cached;
    }
}
