package com.wanderly.analytics;

import com.wanderly.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F12: consumer group {@code analytics-sink} lands {@code user-activity} and {@code saved-events} in the
 * S3 data lake. Independent of the notification and recommendation consumers: same events, its own
 * offsets, and it backfills from the earliest retained offset when first deployed.
 *
 * <p>At-least-once: offsets are committed only after the batch is written. A redelivered batch
 * rewrites the same object keys (see {@link LakeWriter}), and queries also de-duplicate by eventId.
 * If the lake is down the batch is retried every 10 s indefinitely: analytics would rather lag than
 * drop data, and this consumer's lag doesn't affect the user-facing ones.
 */
@Component
public class AnalyticsSink {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSink.class);

    private final LakeWriter lake;

    public AnalyticsSink(LakeWriter lake) {
        this.lake = lake;
    }

    @KafkaListener(topics = {Topics.USER_ACTIVITY, Topics.SAVED_EVENTS}, groupId = "analytics-sink",
            containerFactory = "lakeBatchFactory", autoStartup = "${wanderly.analytics.enabled:true}")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        Map<String, List<ConsumerRecord<String, String>>> byTopicPartition = records.stream()
                .collect(Collectors.groupingBy(r -> r.topic() + "#" + r.partition(), LinkedHashMap::new, Collectors.toList()));
        for (List<ConsumerRecord<String, String>> group : byTopicPartition.values()) {
            ConsumerRecord<String, String> first = group.get(0);
            String key = LakeWriter.objectKey(first.topic(), first.partition(), first.offset(), Instant.ofEpochMilli(first.timestamp()));
            try {
                lake.write(key, group.stream().map(ConsumerRecord::value).collect(Collectors.joining("\n")) + "\n");
            } catch (RuntimeException e) {
                // Batch retries are otherwise silent: say why, then rethrow so the batch is retried (offsets not committed).
                log.warn("Lake write failed for s3://{}/{} ({} events), will retry: {}", lake.bucket(), key, group.size(), e.getMessage());
                throw e;
            }
            log.info("Wrote {} events to s3://{}/{}", group.size(), lake.bucket(), key);
        }
    }

    /** Batch consumer tuned for the lake: up to 1000 records or ~5 s per batch, so files aren't tiny. */
    @Configuration
    static class BatchConfig {

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> lakeBatchFactory(KafkaProperties kafka) {
            Map<String, Object> props = kafka.buildConsumerProperties(null);
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
            props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 64 * 1024);
            props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 5000);
            ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
            factory.setBatchListener(true);
            factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(10_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
            return factory;
        }
    }
}
