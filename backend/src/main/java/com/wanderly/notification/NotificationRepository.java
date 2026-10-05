package com.wanderly.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsBySourceEventId(String sourceEventId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countBySourceEventId(String sourceEventId);
}
