package com.wanderly.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Per-IP rate limits on the public endpoints worth abusing (D58), applied before security and before
 * any work is done. Over the limit: 429 with {@code Retry-After} and a problem-detail body.
 *
 * <ul>
 *   <li>{@code login}: password guessing.</li>
 *   <li>{@code signup}: register, resend code, forgot password. Each one can send an email.</li>
 *   <li>{@code code}: verify and reset-password, on top of the 5-attempts-per-code rule.</li>
 *   <li>{@code planner}: itinerary preview and generate, the most expensive calls.</li>
 * </ul>
 *
 * The client IP is {@code request.getRemoteAddr()}. Behind a proxy or load balancer, Tomcat sets it
 * from {@code X-Forwarded-For}, but only when the request comes from a trusted internal address
 * ({@code server.forward-headers-strategy=native}), so a client can't fake its IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, String> AUTH_RULES = Map.of(
            "/api/auth/login", "login",
            "/api/auth/register", "signup",
            "/api/auth/resend-code", "signup",
            "/api/auth/forgot-password", "signup",
            "/api/auth/verify", "code",
            "/api/auth/reset-password", "code");

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties props, ObjectMapper mapper) {
        this.limiter = limiter;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled() || !"POST".equals(request.getMethod()) || rule(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rule = rule(request.getRequestURI());
        Duration wait = limiter.take(rule, request.getRemoteAddr(), limit(rule));
        log.debug("Rate limit {} for {}: wait {}", rule, request.getRemoteAddr(), wait);
        if (wait.isZero()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Please try again in " + (seconds < 60 ? seconds + " seconds." : (seconds + 59) / 60 + " minutes."));
        problem.setProperty("code", "RATE_LIMITED");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }

    static String rule(String path) {
        String rule = AUTH_RULES.get(path);
        if (rule != null) {
            return rule;
        }
        return path.equals("/api/itineraries") || path.equals("/api/itineraries/preview") ? "planner" : null;
    }

    private RateLimitProperties.Limit limit(String rule) {
        return switch (rule) {
            case "login" -> props.login();
            case "signup" -> props.signup();
            case "code" -> props.code();
            default -> props.planner();
        };
    }
}
