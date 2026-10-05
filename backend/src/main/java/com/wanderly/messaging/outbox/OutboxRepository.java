package com.wanderly.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * The oldest unpublished messages, locked for this relay. {@code SKIP LOCKED} lets several app
     * instances relay at once without sending the same row twice or waiting on each other.
     */
    @Query(nativeQuery = true, value = """
            select * from outbox where published_at is null
            order by id limit :limit
            for update skip locked
            """)
    List<OutboxMessage> lockPending(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    @Modifying
    @Query("delete from OutboxMessage m where m.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
