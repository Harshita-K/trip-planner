package com.wanderly.recommendation;

import com.wanderly.places.Place;

public record RankedPlace(Place place, double score, String reason) {
}
