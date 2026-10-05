package com.wanderly.places;

import com.wanderly.common.GeoPoint;

import java.util.List;
import java.util.Set;

/** Source of places. Implementations wrap an external API or a local dataset. */
public interface PlacesProvider {

    /** Short identifier, used in cache keys so switching providers never serves stale shapes. */
    String name();

    /**
     * Places within {@code radiusKm} of {@code center}. An empty {@code categories} set means
     * every category.
     */
    List<Place> nearby(GeoPoint center, double radiusKm, Set<String> categories);
}
