package com.wanderly.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Admins are the emails in {@code ADMINS} (comma-separated). Nobody is an admin when it's empty. */
@Component
public class AdminPolicy {

    private final Set<String> admins;

    public AdminPolicy(@Value("${wanderly.admins:}") String admins) {
        this.admins = Arrays.stream(admins.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public boolean isAdmin(Jwt jwt) {
        return jwt != null && admins.contains(String.valueOf(jwt.getClaimAsString("email")).toLowerCase(Locale.ROOT));
    }

    public boolean noneConfigured() {
        return admins.isEmpty();
    }
}
