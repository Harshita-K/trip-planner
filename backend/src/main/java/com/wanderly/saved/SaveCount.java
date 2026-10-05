package com.wanderly.saved;

import java.util.UUID;

/** How many users saved an event: the feed's popularity signal. */
public record SaveCount(UUID eventId, long saves) {
}
