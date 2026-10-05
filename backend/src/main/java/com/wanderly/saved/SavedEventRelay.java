package com.wanderly.saved;

import com.wanderly.messaging.ActivityEvent;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.messaging.SavedEventChange;
import com.wanderly.messaging.Topics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes save changes to Kafka only after the database transaction commits, so consumers never
 * see a save that was rolled back.
 */
@Component
public class SavedEventRelay {

    private final EventPublisher publisher;

    public SavedEventRelay(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(SavedEventChange change) {
        publisher.publish(Topics.SAVED_EVENTS, change.userId().toString(), change);
        if (SavedEventChange.SAVED.equals(change.eventType())) {
            publisher.activity(change.userId(), ActivityEvent.EVENT_SAVED, "event", change.itemId().toString(),
                    change.category(), change.location().city());
        }
    }
}
