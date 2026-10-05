package com.wanderly.config;

import com.wanderly.user.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import java.util.Set;

/**
 * Where the JWT comes from (D59): the {@code Authorization: Bearer} header if present (API clients),
 * otherwise the session cookie (the web app).
 *
 * <p><b>CSRF:</b> a cookie is sent by the browser automatically, so for state-changing requests the
 * cookie only counts if the request also carries {@code X-Requested-With}. Other sites can't add a
 * custom header to a cross-site request without a CORS preflight, which this API never approves.
 * Together with {@code SameSite=Lax} that's the standard "custom header" defence; no CSRF tokens needed.
 *
 * <p>Auth endpoints ignore the cookie: logging in (or out) must work even with an expired session.
 */
public class SessionTokenResolver implements BearerTokenResolver {

    public static final String CSRF_HEADER = "X-Requested-With";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String bearer = header.resolve(request);
        if (bearer != null) {
            return bearer;
        }
        if (request.getRequestURI().startsWith("/api/auth/")) {
            return null;
        }
        String cookie = SessionCookie.read(request);
        if (cookie == null) {
            return null;
        }
        boolean safe = SAFE_METHODS.contains(request.getMethod());
        String marker = request.getHeader(CSRF_HEADER);
        return safe || (marker != null && !marker.isBlank()) ? cookie : null;
    }
}
