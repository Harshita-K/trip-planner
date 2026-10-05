package com.wanderly.messaging;

/** Kafka topic names (design doc §6). */
public final class Topics {

    /** Keyed by userId: keeps one user's activity ordered on a single partition. */
    public static final String USER_ACTIVITY = "user-activity";
    /** Keyed by userId: one user's saves and unsaves are consumed in order. */
    public static final String SAVED_EVENTS = "saved-events";
    /** Keyed by userId: requests to message a user (e.g. scheduled reminders). */
    public static final String NOTIFICATIONS = "notifications";
    /** Keyed by recipient email: outgoing transactional email (verification codes) for the email worker. */
    public static final String EMAILS = "emails";
    /** Poison messages that failed processing after retries. */
    public static final String DEAD_LETTER = "dead-letter";

    private Topics() {
    }
}
