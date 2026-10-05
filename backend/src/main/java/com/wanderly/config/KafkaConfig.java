package com.wanderly.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.wanderly.messaging.Topics;
import com.wanderly.notification.EmailInFlightException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic userActivityTopic(WanderlyProperties props) {
        return TopicBuilder.name(Topics.USER_ACTIVITY).partitions(props.kafka().partitions()).replicas(1).build();
    }

    @Bean
    NewTopic savedEventsTopic(WanderlyProperties props) {
        return TopicBuilder.name(Topics.SAVED_EVENTS).partitions(props.kafka().partitions()).replicas(1).build();
    }

    @Bean
    NewTopic notificationsTopic(WanderlyProperties props) {
        return TopicBuilder.name(Topics.NOTIFICATIONS).partitions(props.kafka().partitions()).replicas(1).build();
    }

    /** Short retention: these messages carry one-time codes that are useless after 10 minutes anyway. */
    @Bean
    NewTopic emailsTopic(WanderlyProperties props) {
        return TopicBuilder.name(Topics.EMAILS).partitions(props.kafka().partitions()).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofHours(1).toMillis()))
                .build();
    }

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(Topics.DEAD_LETTER).partitions(1).replicas(1).build();
    }

    /**
     * Retry a failing record twice (1s apart), then park it on the dead-letter topic so one
     * poison message can't block its partition. Malformed JSON is never retried. Spring Boot
     * wires this handler into every @KafkaListener container.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.DEAD_LETTER, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        // An email job whose send lease is held elsewhere waits it out: 12 x 5 s outlasts the 30 s lease.
        handler.setBackOffFunction((record, ex) ->
                causedBy(ex, EmailInFlightException.class) ? new FixedBackOff(5_000L, 12) : null);
        return handler;
    }

    private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
