package com.wanderly.saved;

import com.wanderly.catalog.Event;
import com.wanderly.catalog.EventRepository;
import com.wanderly.common.ApiException;
import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.SavedEventChange;
import com.wanderly.messaging.Topics;
import com.wanderly.messaging.outbox.Outbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Saving an event is the planner's version of "I'm going". It only touches Postgres; what happens
 * <em>because</em> of a save (inbox note with nearby picks, affinity, analytics, the day-before
 * reminder) hangs off a {@link SavedEventChange} written to the transactional outbox in the same
 * transaction (D56), so the save and its message can't disagree. Both operations are idempotent, and
 * only real changes produce a message.
 */
@Service
public class SavedEventService {

    public record Saved(SavedEvent save, Event event) {
    }

    private final SavedEventRepository saves;
    private final EventRepository events;
    private final Outbox outbox;

    public SavedEventService(SavedEventRepository saves, EventRepository events, Outbox outbox) {
        this.saves = saves;
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional
    public Saved save(UUID userId, UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (event.getStartTime().isBefore(Instant.now())) {
            throw ApiException.badRequest("This event has already started");
        }
        if (saves.insertIfAbsent(userId, eventId) == 1) {
            SavedEventChange change = change(SavedEventChange.SAVED, userId, event);
            outbox.enqueue(Topics.SAVED_EVENTS, userId.toString(), change);
            outbox.enqueue(Topics.USER_ACTIVITY, userId.toString(), ActivityEvent.of(userId, ActivityEvent.EVENT_SAVED,
                    "event", eventId.toString(), event.getCategory(), event.getCity()));
        }
        return new Saved(saves.findByUserIdAndEventId(userId, eventId).orElseThrow(), event);
    }

    @Transactional
    public void unsave(UUID userId, UUID eventId) {
        if (saves.deleteByUserIdAndEventId(userId, eventId) == 1) {
            events.findById(eventId).ifPresent(e ->
                    outbox.enqueue(Topics.SAVED_EVENTS, userId.toString(), change(SavedEventChange.UNSAVED, userId, e)));
        }
    }

    /** Most recently saved first; the UI splits upcoming from past. */
    @Transactional(readOnly = true)
    public List<Saved> list(UUID userId) {
        List<SavedEvent> mine = saves.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, Event> byId = events.findAllById(mine.stream().map(SavedEvent::getEventId).toList()).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        return mine.stream().filter(s -> byId.containsKey(s.getEventId()))
                .map(s -> new Saved(s, byId.get(s.getEventId()))).toList();
    }

    private static SavedEventChange change(String type, UUID userId, Event e) {
        return new SavedEventChange(UUID.randomUUID().toString(), type, Instant.now(), userId, e.getId(), e.getTitle(),
                e.getCategory(), e.getStartTime(), new SavedEventChange.Location(e.getLat(), e.getLng(), e.getCity()));
    }
}
