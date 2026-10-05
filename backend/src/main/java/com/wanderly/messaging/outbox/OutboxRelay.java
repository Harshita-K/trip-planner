package com.wanderly.messaging.outbox;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Read side of the transactional outbox (D56): sends pending rows to Kafka in id order and marks
 * them published.
 *
 * <ul>
 *   <li><b>When:</b> right after a commit that enqueued something ({@link #nudge}), and every second
 *       as a safety net (restarts, Kafka outages, other instances' rows).</li>
 *   <li><b>At-least-once:</b> a row is marked only after Kafka acknowledged it (acks=all). A crash in
 *       between sends it again; every consumer already de-duplicates by event id.</li>
 *   <li><b>Order:</b> rows go out one by one, oldest first, and a failure stops the batch, so a later
 *       message for the same key never overtakes an earlier one.</li>
 *   <li><b>Several instances:</b> {@code FOR UPDATE SKIP LOCKED} splits the work without duplicates.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    static final int BATCH = 100;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration KEEP_PUBLISHED = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final ReentrantLock draining = new ReentrantLock();
    private final ExecutorService nudges = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("outbox-relay").factory());

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Relay soon, off the request thread. Cheap to call often: concurrent drains collapse into one. */
    void nudge() {
        nudges.execute(this::drain);
    }

    @Scheduled(fixedDelayString = "${wanderly.outbox.poll-interval:PT1S}")
    public void poll() {
        drain();
    }

    /** Sends everything pending (in batches). @return how many messages were published. */
    public int drain() {
        if (!draining.tryLock()) {
            return 0;   // this instance is already draining; that pass will pick these rows up too
        }
        try {
            int total = 0;
            int sent;
            do {
                sent = publishBatch();
                total += Math.max(sent, 0);
            } while (sent == BATCH);
            return total;
        } finally {
            draining.unlock();
        }
    }

    /** @return messages published in this batch, or -1 if one failed (the rest wait for the next pass). */
    private int publishBatch() {
        Integer result = tx.execute(status -> {
            List<OutboxMessage> pending = repository.lockPending(BATCH);
            int published = 0;
            for (OutboxMessage message : pending) {
                try {
                    kafka.send(message.getTopic(), message.getKey(), message.getPayload())
                            .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    message.markFailed(reason);
                    log.warn("Outbox message {} to {} not published yet (attempt {}): {}",
                            message.getId(), message.getTopic(), message.getAttempts(), reason);
                    return -1;   // keep order: nothing after it goes out before it does
                }
                message.markPublished();
                published++;
            }
            return published;
        });
        return result == null ? 0 : result;
    }

    @PreDestroy
    void stop() {
        nudges.shutdownNow();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanUp() {
        Integer removed = tx.execute(status -> repository.deletePublishedBefore(Instant.now().minus(KEEP_PUBLISHED)));
        if (removed != null && removed > 0) {
            log.info("Removed {} published outbox message(s)", removed);
        }
    }
}
