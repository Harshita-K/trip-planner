package com.wanderly.user;

import com.wanderly.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** The JWT subject is the user's id. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        if (jwt == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return UUID.fromString(jwt.getSubject());
    }

    /** For public endpoints that personalise when a token is present. */
    public static UUID idOrNull(Jwt jwt) {
        return jwt == null ? null : UUID.fromString(jwt.getSubject());
    }
}
