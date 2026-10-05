package com.wanderly.notification;

import com.wanderly.user.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Inbox view of everything the notification consumer has sent — handy for demos. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return notifications.forUser(CurrentUser.id(jwt)).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getType(), n.getChannel(), n.getSubject(),
                        n.getBody(), n.getCreatedAt()))
                .toList();
    }

    public record NotificationResponse(UUID id, String type, String channel, String subject, String body,
                                       Instant createdAt) {
    }
}
