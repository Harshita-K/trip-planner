package com.wanderly.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write side of the transactional outbox (D56). {@link #enqueue} stores the message in the caller's
 * database transaction, so the change and its message commit or roll back together. After commit the
 * relay is nudged to send it right away; if the app dies first, the relay finds it on its next pass.
 */
@Component
public class Outbox {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    private final OutboxRelay relay;

    public Outbox(OutboxRepository repository, ObjectMapper mapper, OutboxRelay relay) {
        this.repository = repository;
        this.mapper = mapper;
        this.relay = relay;
    }

    /** Must run inside a transaction: the whole point is committing with the change it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String key, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserialisable event for " + topic, e);
        }
        repository.save(new OutboxMessage(topic, key, json));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.nudge();
                }
            });
        }
    }
}
