package com.wanderly.notification;

/**
 * Another delivery of the same email job holds the send lease (or crashed while holding it).
 * Retried with a slow backoff until the lease is released or expires.
 */
public class EmailInFlightException extends RuntimeException {

    public EmailInFlightException(String jobId) {
        super("Email job " + jobId + " is already being sent");
    }
}
