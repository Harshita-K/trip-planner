package com.wanderly.notification;

import com.wanderly.user.User;
import com.wanderly.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Idempotent delivery keyed on the Kafka event id.
 *
 * <p>Kafka gives at-least-once delivery, so the same event can arrive twice (consumer restart
 * before commit, rebalance, producer retry). We record the notification and send inside one DB
 * transaction; {@code notifications.source_event_id} is UNIQUE, so a duplicate either sees the
 * existing row and skips, or — if two copies race — loses on the constraint. If sending throws,
 * the row rolls back and Kafka retries.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final NotificationSender sender;

    public NotificationService(NotificationRepository notifications, UserRepository users, NotificationSender sender) {
        this.notifications = notifications;
        this.users = users;
        this.sender = sender;
    }

    /** In-app channel: shows in the inbox, no email. For things the user just did themselves. */
    static final String INBOX = "inbox";

    /** Inbox plus email. @return false if this source event was already handled. */
    @Transactional
    public boolean deliver(UUID userId, String type, String subject, String body, String sourceEventId) {
        return deliver(userId, type, subject, body, sourceEventId, true);
    }

    /** @return false if this source event was already handled. */
    @Transactional
    public boolean deliver(UUID userId, String type, String subject, String body, String sourceEventId, boolean email) {
        if (notifications.existsBySourceEventId(sourceEventId)) {
            log.debug("Skipping duplicate event {}", sourceEventId);
            return false;
        }
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Dropping notification {} for unknown user {}", sourceEventId, userId);
            return false;
        }
        notifications.saveAndFlush(new Notification(userId, type, email ? sender.channel() : INBOX, subject, body,
                sourceEventId));
        if (email) {
            sender.send(user.getEmail(), subject, body);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<Notification> forUser(UUID userId) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
