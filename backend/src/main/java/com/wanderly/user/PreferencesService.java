package com.wanderly.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PreferencesService {

    private final UserPreferencesRepository repository;

    public PreferencesService(UserPreferencesRepository repository) {
        this.repository = repository;
    }

    /** Preferences for a user, or {@link Preferences#DEFAULT} for anonymous visitors. */
    @Transactional(readOnly = true)
    public Preferences forUser(UUID userId) {
        if (userId == null) {
            return Preferences.DEFAULT;
        }
        return repository.findById(userId)
                .map(p -> new Preferences(normalise(p.getInterests()), p.getBudgetLevel(), p.getTravelPace()))
                .orElse(Preferences.DEFAULT);
    }

    public static Set<String> normalise(Collection<String> interests) {
        if (interests == null) {
            return Set.of();
        }
        return interests.stream()
                .filter(i -> i != null && !i.isBlank())
                .map(i -> i.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
