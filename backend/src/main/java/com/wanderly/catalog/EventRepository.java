package com.wanderly.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    List<Event> findByStartTimeAfterOrderByStartTimeAsc(Instant after);

    List<Event> findByCreatedByOrderByStartTimeDesc(UUID createdBy);

    long countByCreatedByAndStartTimeAfter(UUID createdBy, Instant after);
}
