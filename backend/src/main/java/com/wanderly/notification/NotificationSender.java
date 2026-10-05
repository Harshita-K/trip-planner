package com.wanderly.notification;

/** Delivery channel. Local dev logs; production would be Amazon SES (email) / SNS (SMS, push). */
public interface NotificationSender {

    String channel();

    void send(String toEmail, String subject, String body);

    /**
     * Send with a fixed Message-ID. Retries of the same job reuse it, so if a duplicate ever goes
     * out after a crash, mail systems can recognise it as the same message.
     */
    default void send(String toEmail, String subject, String body, String messageId) {
        send(toEmail, subject, body);
    }
}
