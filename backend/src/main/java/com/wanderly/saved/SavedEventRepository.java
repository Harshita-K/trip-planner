package com.wanderly.saved;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedEventRepository extends JpaRepository<SavedEvent, UUID> {

    List<SavedEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<SavedEvent> findByUserIdAndEventId(UUID userId, UUID eventId);

    /**
     * Idempotent save in one statement: concurrent double-clicks can't both insert (unique constraint)
     * and neither fails. Returns 1 if this call created the save, 0 if it already existed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into saved_events (id, user_id, event_id, created_at)
            values (gen_random_uuid(), :userId, :eventId, now())
            on conflict (user_id, event_id) do nothing
            """)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("eventId") UUID eventId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SavedEvent s where s.userId = :userId and s.eventId = :eventId")
    int deleteByUserIdAndEventId(@Param("userId") UUID userId, @Param("eventId") UUID eventId);

    @Query("""
            select new com.wanderly.saved.UpcomingSave(s.userId, e.id, e.title, e.startTime, e.venue, e.city)
            from SavedEvent s, Event e
            where s.eventId = e.id and e.startTime > :from and e.startTime <= :to
            """)
    List<UpcomingSave> findStartingBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select new com.wanderly.saved.SaveCount(s.eventId, count(s))
            from SavedEvent s where s.eventId in :eventIds group by s.eventId
            """)
    List<SaveCount> countByEventIds(@Param("eventIds") Collection<UUID> eventIds);
}
