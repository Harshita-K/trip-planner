package com.wanderly.user;

import com.wanderly.config.WanderlyProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The browser session (D59): the JWT lives in an {@code httpOnly} cookie that page scripts can't read,
 * so an XSS bug can't steal a token to replay elsewhere. {@code SameSite=Lax} keeps other sites' forms
 * from sending it, {@code Path=/api} keeps it off everything but API calls, and it expires with the token.
 * API clients (Swagger, curl) can still use {@code Authorization: Bearer}.
 */
@Component
public class SessionCookie {

    public static final String NAME = "wanderly_session";
    static final String PATH = "/api";

    private final Duration ttl;
    private final boolean secure;

    public SessionCookie(WanderlyProperties props) {
        this.ttl = props.security().tokenTtl();
        this.secure = props.security().cookieSecure();
    }

    public void set(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, ttl).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path(PATH).maxAge(maxAge).build();
    }
}
