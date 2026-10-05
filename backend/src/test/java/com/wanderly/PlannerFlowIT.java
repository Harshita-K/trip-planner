package com.wanderly;

import com.wanderly.messaging.CodeCipher;
import com.wanderly.messaging.EmailJob;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.messaging.NotificationRequest;
import com.wanderly.messaging.Topics;
import com.wanderly.messaging.outbox.Outbox;
import com.wanderly.messaging.outbox.OutboxRepository;
import com.wanderly.notification.NotificationRepository;
import com.wanderly.notification.NotificationSender;
import com.wanderly.notification.ReminderScheduler;
import com.wanderly.user.OtpStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end over real Postgres, Kafka and Redis (Testcontainers; needs Docker). Run with
 * {@code ./mvnw verify}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"wanderly.mail.enabled=false",
        "wanderly.places.live=false", "wanderly.analytics.enabled=false", "wanderly.travel.osrm-url=",
        // No live geocoding in tests: venue lookups fail fast and fall back to the city centre.
        "wanderly.places.osm.photon-url=http://127.0.0.1:9",
        "wanderly.admins=admin@example.com",
        // Every test shares 127.0.0.1 and signs up many users: loosen these two; the rate-limit test uses its own IPs.
        "wanderly.rate-limit.signup.capacity=1000", "wanderly.rate-limit.code.capacity=1000"})
@Testcontainers
class PlannerFlowIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Captures outgoing email so tests can read codes and count duplicate sends. */
    static final Map<String, List<String>> SUBJECTS_BY_EMAIL = new ConcurrentHashMap<>();
    static final Map<String, List<String>> BODIES_BY_EMAIL = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> SENDS_BY_MESSAGE_ID = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CapturingMail {
        @Bean
        @Primary
        NotificationSender capturingSender() {
            return new NotificationSender() {
                @Override
                public String channel() {
                    return "email";
                }

                @Override
                public void send(String toEmail, String subject, String body) {
                    send(toEmail, subject, body, null);
                }

                @Override
                public void send(String toEmail, String subject, String body, String messageId) {
                    SUBJECTS_BY_EMAIL.computeIfAbsent(toEmail, k -> new CopyOnWriteArrayList<>()).add(subject);
                    BODIES_BY_EMAIL.computeIfAbsent(toEmail, k -> new CopyOnWriteArrayList<>()).add(body);
                    if (messageId != null) {
                        SENDS_BY_MESSAGE_ID.computeIfAbsent(messageId, k -> new AtomicInteger()).incrementAndGet();
                    }
                }
            };
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EventPublisher publisher;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    OtpStore otpStore;

    @Autowired
    ReminderScheduler reminders;

    @Autowired
    CodeCipher cipher;

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxRepository outboxMessages;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void savingAnEventFansOutToTheInboxAndIsIdempotent() {
        String token = register();
        String email = emailOf(token);
        call(HttpMethod.PUT, "/api/users/me/preferences", token,
                Map.of("interests", List.of("history", "museum"), "travelPace", "balanced"), MAP);
        String eventId = seededEvent("Heritage Walk");

        // Save twice (double-click): same result, one save, one message.
        ResponseEntity<Map<String, Object>> first = call(HttpMethod.PUT, "/api/saved-events/" + eventId, token, null, MAP);
        ResponseEntity<Map<String, Object>> second = call(HttpMethod.PUT, "/api/saved-events/" + eventId, token, null, MAP);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("savedAt")).isEqualTo(first.getBody().get("savedAt"));
        assertThat(call(HttpMethod.GET, "/api/saved-events", token, null, LIST).getBody()).hasSize(1);

        // Asynchronous fan-out: saved-events topic -> notification consumer (with F7 nearby picks), inbox only.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Map<String, Object>> inbox = call(HttpMethod.GET, "/api/notifications", token, null, LIST).getBody();
            assertThat(inbox).filteredOn(n -> n.get("subject").toString().startsWith("Saved: Heritage Walk"))
                    .singleElement().satisfies(n -> {
                        assertThat(n.get("body").toString()).contains("Eat nearby", "remind you the day before");
                        assertThat(n.get("channel")).isEqualTo("inbox");
                    });
        });
        assertThat(SUBJECTS_BY_EMAIL.get(email)).noneMatch(subject -> subject.startsWith("Saved"));

        // Unsave is idempotent too.
        assertThat(call(HttpMethod.DELETE, "/api/saved-events/" + eventId, token, null, MAP).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, "/api/saved-events/" + eventId, token, null, MAP).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/saved-events", token, null, LIST).getBody()).isEmpty();

        // What's around an event is public, like browsing.
        ResponseEntity<Map<String, Object>> nearby = call(HttpMethod.GET, "/api/events/" + eventId + "/nearby", null, null, MAP);
        assertThat(nearby.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) nearby.getBody().get("food")).isNotEmpty();
    }

    @Test
    void organisersListEventsInAnyCityAndOnlyTheyCanChangeThem() {
        String organiser = register();
        String stranger = register();
        Instant startsAt = Instant.now().plus(Duration.ofDays(4));
        Map<String, Object> listing = Map.of("title", "Lake Pichola Sunset Concert", "category", "music",
                "venue", "Ambrai Ghat", "city", "Udaipur", "lat", 24.5854, "lng", 73.7125,
                "startTime", startsAt.toString(), "price", 0, "description", "Folk music by the lake.");

        ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, "/api/events", organiser, listing, MAP);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("community", true).containsEntry("city", "Udaipur");
        // Geocoding is unavailable in tests, so the venue falls back to the city centre.
        assertThat(created.getBody()).containsEntry("lat", 24.5854).containsEntry("lng", 73.7125);
        String id = created.getBody().get("id").toString();

        // A city with no seeded events now has one, publicly.
        Map<String, Object> page = call(HttpMethod.GET, "/api/events?city=udaipur", null, null, MAP).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).extracting(e -> e.get("id").toString()).contains(id);
        assertThat(call(HttpMethod.GET, "/api/events/mine", organiser, null, LIST).getBody())
                .extracting(e -> e.get("id")).containsExactly(id);

        Map<String, Object> edited = new java.util.HashMap<>(listing);
        edited.put("title", "Lake Pichola Sunset Concert (moved to 6 pm)");
        assertThat(call(HttpMethod.PUT, "/api/events/" + id, stranger, edited, MAP).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.DELETE, "/api/events/" + id, stranger, null, MAP).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/events/" + id, organiser, edited, MAP).getBody())
                .containsEntry("title", "Lake Pichola Sunset Concert (moved to 6 pm)");

        // Seeded events belong to no one: only admins could change them.
        assertThat(call(HttpMethod.DELETE, "/api/events/" + seededEvent("Heritage Walk"), organiser, null, MAP).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Validation: no past events, no unknown categories.
        Map<String, Object> past = new java.util.HashMap<>(listing);
        past.put("startTime", Instant.now().minus(Duration.ofHours(1)).toString());
        assertThat(call(HttpMethod.POST, "/api/events", organiser, past, MAP).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> odd = new java.util.HashMap<>(listing);
        odd.put("category", "cockfighting");
        assertThat(call(HttpMethod.POST, "/api/events", organiser, odd, MAP).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Deleting the event removes it from savers' plans too.
        call(HttpMethod.PUT, "/api/saved-events/" + id, stranger, null, MAP);
        assertThat(call(HttpMethod.DELETE, "/api/events/" + id, organiser, null, MAP).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/api/saved-events", stranger, null, LIST).getBody()).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/events/" + id, null, null, MAP).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void remindsAboutSavedEventsAndTripsStartingTomorrowExactlyOnce() {
        String token = register();
        String email = emailOf(token);
        // Pretend it's at least 10:00 IST today, so trip reminders (from 09:00 IST) are due.
        Instant tenAm = LocalDate.now(IST).atTime(LocalTime.of(10, 0)).atZone(IST).toInstant();
        Instant now = Instant.now().isAfter(tenAm) ? Instant.now() : tenAm;

        String eventId = call(HttpMethod.POST, "/api/events", token, Map.of("title", "Reminder Test Gig", "category", "music",
                "venue", "Somewhere", "city", "Bengaluru", "lat", 12.9716, "lng", 77.5946,
                "startTime", now.plus(Duration.ofHours(3)).toString()), MAP).getBody().get("id").toString();
        call(HttpMethod.PUT, "/api/saved-events/" + eventId, token, null, MAP);

        LocalDate tomorrow = now.atZone(IST).toLocalDate().plusDays(1);
        ResponseEntity<Map<String, Object>> trip = call(HttpMethod.POST, "/api/itineraries", token, Map.of(
                "destination", "Bengaluru", "lat", 12.9716, "lng", 77.5946,
                "startDate", tomorrow.toString(), "endDate", tomorrow.plusDays(1).toString()), MAP);
        assertThat(trip.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tripReminderId = "reminder:itinerary:" + trip.getBody().get("id");

        assertThat(reminders.sendReminders(now)).isGreaterThanOrEqualTo(2);
        await().atMost(Duration.ofSeconds(30)).until(() -> notifications.existsBySourceEventId(tripReminderId));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(SUBJECTS_BY_EMAIL.get(email))
                .contains("Coming up: Reminder Test Gig", "Tomorrow: your trip to Bengaluru"));
        assertThat(BODIES_BY_EMAIL.get(email)).anySatisfy(body ->
                assertThat(body).contains("Here's day 1:", "Lunch", "1 more day planned"));

        // Hourly reruns don't repeat a reminder that's already been delivered.
        reminders.sendReminders(now.plus(Duration.ofHours(1)));
        reminders.sendReminders(now.plus(Duration.ofHours(2)));
        assertThat(notifications.countBySourceEventId(tripReminderId)).isEqualTo(1);
        assertThat(SUBJECTS_BY_EMAIL.get(email)).containsOnlyOnce("Tomorrow: your trip to Bengaluru");
    }

    @Test
    void forgotPasswordResetsItAndSignsOutOtherSessions() {
        String oldToken = register();
        String email = emailOf(oldToken);

        // Unknown emails get the same 202 and no email.
        String nobody = "nobody-" + UUID.randomUUID() + "@example.com";
        assertThat(call(HttpMethod.POST, "/api/auth/forgot-password", null, Map.of("email", nobody), MAP).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        int sentBefore = BODIES_BY_EMAIL.get(email).size();
        assertThat(call(HttpMethod.POST, "/api/auth/forgot-password", null, Map.of("email", email), MAP).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(30)).until(() -> BODIES_BY_EMAIL.get(email).size() > sentBefore);
        assertThat(SUBJECTS_BY_EMAIL.get(email).get(sentBefore)).endsWith("is your Wanderly password reset code");
        String code = codeSentTo(email);

        // A wrong code is rejected; the right one works exactly once.
        assertThat(resetPassword(email, otherThan(code), "a-brand-new-password").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map<String, Object>> reset = resetPassword(email, code, "a-brand-new-password");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newToken = reset.getBody().get("accessToken").toString();

        assertThat(resetPassword(email, code, "yet-another-password").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/users/me", oldToken, null, MAP).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/users/me", newToken, null, MAP).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, "correct-horse-battery").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, "a-brand-new-password").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(BODIES_BY_EMAIL).doesNotContainKey(nobody);
    }

    @Test
    void loginIsRateLimitedPerClientIp() {
        String ip = "203.0.113." + (1 + new java.util.Random().nextInt(250));
        Map<String, Object> wrong = Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "password", "wrong-password");

        for (int i = 0; i < 10; i++) {
            assertThat(loginFrom(ip, wrong).statusCode()).isEqualTo(401);
        }
        java.net.http.HttpResponse<String> limited = loginFrom(ip, wrong);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(s -> assertThat(Integer.parseInt(s)).isPositive());
        assertThat(limited.body()).contains("RATE_LIMITED");

        // Another client is unaffected.
        assertThat(loginFrom("198.51.100." + (1 + new java.util.Random().nextInt(250)), wrong).statusCode()).isEqualTo(401);
    }

    @Test
    void browserSessionIsAnHttpOnlyCookieWithCsrfProtection() {
        String email = emailOf(register());
        ResponseEntity<Map<String, Object>> loggedIn = login(email, "correct-horse-battery");
        String setCookie = loggedIn.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("wanderly_session=").contains("HttpOnly", "SameSite=Lax", "Path=/api");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        // Reads work with the cookie alone.
        HttpHeaders cookieOnly = new HttpHeaders();
        cookieOnly.add(HttpHeaders.COOKIE, cookie);
        assertThat(rest.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(cookieOnly), MAP).getBody())
                .containsEntry("email", email);

        // Writes need the CSRF header too: a forged cross-site form can't add it.
        Map<String, Object> prefs = Map.of("interests", List.of("music"), "travelPace", "relaxed");
        assertThat(rest.exchange("/api/users/me/preferences", HttpMethod.PUT, new HttpEntity<>(prefs, cookieOnly), MAP)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders withMarker = new HttpHeaders();
        withMarker.addAll(cookieOnly);
        withMarker.add("X-Requested-With", "wanderly");
        assertThat(rest.exchange("/api/users/me/preferences", HttpMethod.PUT, new HttpEntity<>(prefs, withMarker), MAP)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Logout deletes the cookie; a broken cookie is deleted by the 401 that rejects it.
        String cleared = rest.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(withMarker), MAP)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cleared).startsWith("wanderly_session=;").contains("Max-Age=0");
        HttpHeaders stale = new HttpHeaders();
        stale.add(HttpHeaders.COOKIE, "wanderly_session=not-a-token");
        ResponseEntity<Map<String, Object>> rejected = rest.exchange("/api/users/me", HttpMethod.GET, new HttpEntity<>(stale), MAP);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void outboxRelaysCommittedMessagesAndNeverRolledBackOnes() {
        String token = register();
        UUID userId = UUID.fromString(call(HttpMethod.GET, "/api/users/me", token, null, MAP).getBody().get("id").toString());
        String committed = "outbox-ok-" + UUID.randomUUID();
        String rolledBack = "outbox-rb-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(s -> outbox.enqueue(Topics.NOTIFICATIONS, userId.toString(),
                new NotificationRequest(committed, "test", Instant.now(), userId, "Committed", "Sent")));
        tx.executeWithoutResult(s -> {
            outbox.enqueue(Topics.NOTIFICATIONS, userId.toString(),
                    new NotificationRequest(rolledBack, "test", Instant.now(), userId, "Rolled back", "Never sent"));
            s.setRollbackOnly();
        });

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications.existsBySourceEventId(committed));
        assertThat(outboxMessages.findAll()).filteredOn(m -> m.getPayload().contains(committed))
                .singleElement().satisfies(m -> assertThat(m.getPublishedAt()).isNotNull());
        assertThat(outboxMessages.findAll()).noneMatch(m -> m.getPayload().contains(rolledBack));
        assertThat(notifications.existsBySourceEventId(rolledBack)).isFalse();

        // Enqueueing outside a transaction is a bug, not a silent best-effort send.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> outbox.enqueue(Topics.NOTIFICATIONS, "k", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void notificationConsumerIsIdempotent() {
        String token = register();
        UUID userId = UUID.fromString(call(HttpMethod.GET, "/api/users/me", token, null, MAP).getBody().get("id").toString());
        String duplicateId = "dup-" + UUID.randomUUID();
        String markerId = "marker-" + UUID.randomUUID();

        // Same key (userId) -> same partition -> processed in order, so once the marker lands
        // both duplicates have been consumed.
        NotificationRequest duplicate = new NotificationRequest(duplicateId, "test", Instant.now(), userId, "Hello", "Once");
        publisher.publish(Topics.NOTIFICATIONS, userId.toString(), duplicate);
        publisher.publish(Topics.NOTIFICATIONS, userId.toString(), duplicate);
        publisher.publish(Topics.NOTIFICATIONS, userId.toString(),
                new NotificationRequest(markerId, "test", Instant.now(), userId, "Marker", "Done"));

        await().atMost(Duration.ofSeconds(30)).until(() -> notifications.existsBySourceEventId(markerId));
        assertThat(notifications.countBySourceEventId(duplicateId)).isEqualTo(1);
    }

    @Test
    void generatesAMultiDayItinerary() {
        String token = register();
        LocalDate start = LocalDate.now().plusDays(10);
        Map<String, Object> request = Map.of(
                "destination", "Bengaluru", "lat", 12.9716, "lng", 77.5946,
                "startDate", start.toString(), "endDate", start.plusDays(1).toString(),
                "interests", List.of("history", "nature"));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/itineraries", token, request, MAP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) response.getBody().get("plan");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) plan.get("days");
        assertThat(days).hasSize(2).allSatisfy(d -> assertThat((List<?>) d.get("stops")).isNotEmpty());

        String id = response.getBody().get("id").toString();
        assertThat(call(HttpMethod.GET, "/api/itineraries/" + id, token, null, MAP).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void emailMustBeVerifiedBeforeLogin() {
        String email = "verify-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> creds = Map.of("email", email, "password", "correct-horse-battery");
        signUp(email);

        ResponseEntity<Map<String, Object>> blocked = call(HttpMethod.POST, "/api/auth/login", null, creds, MAP);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).containsEntry("code", "EMAIL_NOT_VERIFIED");

        String code = codeSentTo(email);
        assertThat(verify(email, otherThan(code)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(verify(email, code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/auth/login", null, creds, MAP).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void codeWorksOnlyOnce() {
        String email = "once-" + UUID.randomUUID() + "@example.com";
        signUp(email);
        String code = codeSentTo(email);

        assertThat(verify(email, code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify(email, code).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fiveWrongAttemptsBurnTheCode() {
        String email = "burn-" + UUID.randomUUID() + "@example.com";
        signUp(email);
        String code = codeSentTo(email);

        for (int i = 0; i < 5; i++) {
            assertThat(verify(email, otherThan(code)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<Map<String, Object>> afterBurn = verify(email, code);
        assertThat(afterBurn.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(afterBurn.getBody().get("detail")).isEqualTo("That code is invalid or has expired.");
    }

    @Test
    void concurrentCorrectSubmissionsOnlyOneWins() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        signUp(email);
        String code = codeSentTo(email);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<HttpStatus>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(verify(email, code).getStatusCode().value());
            }));
        }
        start.countDown();
        long wins = 0;
        for (Future<HttpStatus> r : results) {
            if (r.get() == HttpStatus.OK) {
                wins++;
            }
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
    }

    @Test
    void signUpDoesNotRevealExistingAccounts() {
        String existing = "taken-" + UUID.randomUUID() + "@example.com";
        signUp(existing);
        verify(existing, codeSentTo(existing));
        String fresh = "fresh-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<Map<String, Object>> forExisting = signUp(existing);
        ResponseEntity<Map<String, Object>> forFresh = signUp(fresh);

        // Same status and same body (apart from the echoed address) either way.
        assertThat(forExisting.getStatusCode()).isEqualTo(forFresh.getStatusCode());
        assertThat(forExisting.getBody().get("message")).isEqualTo(forFresh.getBody().get("message"));
        assertThat(call(HttpMethod.POST, "/api/auth/resend-code", null, Map.of("email", existing), MAP).getStatusCode())
                .isEqualTo(call(HttpMethod.POST, "/api/auth/resend-code", null, Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com"), MAP).getStatusCode());
        // The real owner is told by email instead.
        await().atMost(Duration.ofSeconds(30)).until(() ->
                SUBJECTS_BY_EMAIL.getOrDefault(existing, List.of()).contains("You already have a Wanderly account"));
    }

    @Test
    void emailWorkerIsIdempotentAndSkipsStaleCodes() {
        String email = "idem-" + UUID.randomUUID() + "@example.com";
        String jobId = UUID.randomUUID().toString();
        assertThat(otpStore.issue(email, "424242", jobId)).isTrue();
        EmailJob job = EmailJob.verificationCode(cipher, jobId, email, "Idem", "424242");
        EmailJob stale = EmailJob.verificationCode(cipher, UUID.randomUUID().toString(), email, "Idem", "999999");
        EmailJob marker = EmailJob.accountExists(email, "Idem");

        // Simulate redelivery: same job twice, plus a job for a code that isn't current.
        // All keyed by email -> one partition -> processed in order, so the marker arriving means the rest are done.
        publisher.publish(Topics.EMAILS, email, job);
        publisher.publish(Topics.EMAILS, email, job);
        publisher.publish(Topics.EMAILS, email, stale);
        publisher.publish(Topics.EMAILS, email, marker);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                SENDS_BY_MESSAGE_ID.containsKey("<" + marker.jobId() + "@wanderly.local>"));

        assertThat(SENDS_BY_MESSAGE_ID.get("<" + jobId + "@wanderly.local>")).hasValue(1);
        assertThat(SENDS_BY_MESSAGE_ID).doesNotContainKey("<" + stale.jobId() + "@wanderly.local>");
    }

    @Test
    void citySearchIsInstantAndCoverageIsReported() {
        ResponseEntity<List<Map<String, Object>>> popular = call(HttpMethod.GET, "/api/cities", null, null, LIST);
        assertThat(popular.getBody()).extracting(c -> c.get("name")).startsWith("Bengaluru", "Jaipur");

        ResponseEntity<List<Map<String, Object>>> typed = call(HttpMethod.GET, "/api/cities?q=ud", null, null, LIST);
        assertThat(typed.getBody()).first().satisfies(c -> {
            assertThat(c.get("name")).isEqualTo("Udaipur");
            assertThat(c.get("region")).isEqualTo("Rajasthan");
            assertThat(c.get("curated")).isEqualTo(false);
        });

        // No OpenTripMap key in tests: only curated cities have places.
        Map<String, Object> coverage = call(HttpMethod.GET, "/api/places/coverage", null, null, MAP).getBody();
        assertThat(coverage).containsEntry("liveData", false);
        assertThat(coverage.get("curatedCities")).asString().contains("Bengaluru", "Jaipur");
    }

    @Test
    void nearbyPlacesArePublic() {
        ResponseEntity<List<Map<String, Object>>> response =
                call(HttpMethod.GET, "/api/places/nearby?lat=12.9716&lng=77.5946&radiusKm=3", null, null, LIST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    private String register() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        signUp(email);
        ResponseEntity<Map<String, Object>> verified = verify(email, codeSentTo(email));
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        return verified.getBody().get("accessToken").toString();
    }

    private ResponseEntity<Map<String, Object>> signUp(String email) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "password", "correct-horse-battery", "name", "Test User"), MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("verificationRequired", true).doesNotContainKey("accessToken");
        return response;
    }

    private ResponseEntity<Map<String, Object>> verify(String email, String code) {
        return call(HttpMethod.POST, "/api/auth/verify", null, Map.of("email", email, "code", code), MAP);
    }

    /** The email is sent asynchronously by the worker, so wait for it. */
    private static String codeSentTo(String email) {
        await().atMost(Duration.ofSeconds(30)).until(() -> !BODIES_BY_EMAIL.getOrDefault(email, List.of()).isEmpty());
        List<String> bodies = BODIES_BY_EMAIL.get(email);
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(bodies.get(bodies.size() - 1));
        assertThat(m.find()).as("verification code in email to " + email).isTrue();
        return m.group(1);
    }

    private static String otherThan(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private ResponseEntity<Map<String, Object>> resetPassword(String email, String code, String password) {
        return call(HttpMethod.POST, "/api/auth/reset-password", null,
                Map.of("email", email, "code", code, "password", password), MAP);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        return call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", password), MAP);
    }

    /**
     * A plain JDK client on purpose: TestRestTemplate's Apache client honours Retry-After and would
     * quietly wait out the 429 and retry.
     */
    private java.net.http.HttpResponse<String> loginFrom(String ip, Map<String, Object> body) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", ip)   // trusted: the test client connects from 127.0.0.1, an internal proxy
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build();
            return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String emailOf(String token) {
        return call(HttpMethod.GET, "/api/users/me", token, null, MAP).getBody().get("email").toString();
    }

    @SuppressWarnings("unchecked")
    private String seededEvent(String titlePrefix) {
        Map<String, Object> page = call(HttpMethod.GET, "/api/events?city=Bengaluru&size=50", null, null, MAP).getBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .filter(e -> e.get("title").toString().startsWith(titlePrefix))
                .findFirst().orElseThrow().get("id").toString();
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body,
                                       ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}
