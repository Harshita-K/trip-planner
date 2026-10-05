package com.wanderly.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void mapsEndpointsToTheirLimits() {
        assertThat(RateLimitFilter.rule("/api/auth/login")).isEqualTo("login");
        assertThat(RateLimitFilter.rule("/api/auth/register")).isEqualTo("signup");
        assertThat(RateLimitFilter.rule("/api/auth/resend-code")).isEqualTo("signup");
        assertThat(RateLimitFilter.rule("/api/auth/forgot-password")).isEqualTo("signup");
        assertThat(RateLimitFilter.rule("/api/auth/verify")).isEqualTo("code");
        assertThat(RateLimitFilter.rule("/api/auth/reset-password")).isEqualTo("code");
        assertThat(RateLimitFilter.rule("/api/itineraries/preview")).isEqualTo("planner");
        assertThat(RateLimitFilter.rule("/api/itineraries")).isEqualTo("planner");
        assertThat(RateLimitFilter.rule("/api/events")).isNull();
        assertThat(RateLimitFilter.rule("/api/auth/logout")).isNull();
    }
}
