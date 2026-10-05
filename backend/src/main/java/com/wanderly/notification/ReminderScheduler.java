package com.wanderly.notification;

import com.wanderly.itinerary.Itinerary;
import com.wanderly.itinerary.ItineraryPlan;
import com.wanderly.itinerary.ItineraryRepository;
import com.wanderly.messaging.EventPublisher;
import com.wanderly.messaging.NotificationRequest;
import com.wanderly.messaging.Topics;
import com.wanderly.saved.SavedEventRepository;
import com.wanderly.saved.UpcomingSave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * F11 reminders, checked every hour (D53):
 * <ul>
 *   <li><b>Saved events</b> starting within 24 hours.</li>
 *   <li><b>Saved trips</b> (itineraries) starting tomorrow, sent from 09:00 IST the day before,
 *       with day 1's timetable in the message.</li>
 * </ul>
 * Each reminder has a deterministic id ({@code reminder:event:{user}:{event}},
 * {@code reminder:itinerary:{id}}) that becomes the notification's unique source id, so hourly reruns,
 * restarts or several instances still produce one reminder. Ids already in the inbox aren't even
 * re-queued. A missed run is caught up by the next one. In AWS this is an EventBridge-scheduled Lambda
 * emitting the same messages.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    /** Don't email trip reminders in the middle of the night. */
    static final LocalTime TRIP_REMINDERS_FROM = LocalTime.of(9, 0);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);

    private final SavedEventRepository saves;
    private final ItineraryRepository itineraries;
    private final NotificationRepository notifications;
    private final EventPublisher publisher;

    public ReminderScheduler(SavedEventRepository saves, ItineraryRepository itineraries,
                             NotificationRepository notifications, EventPublisher publisher) {
        this.saves = saves;
        this.itineraries = itineraries;
        this.notifications = notifications;
        this.publisher = publisher;
    }

    @Scheduled(cron = "${wanderly.notifications.reminder-cron}")
    public void run() {
        sendReminders(Instant.now());
    }

    /** @return how many reminders were queued. */
    public int sendReminders(Instant now) {
        int queued = 0;
        for (UpcomingSave save : saves.findStartingBetween(now, now.plus(Duration.ofHours(24)))) {
            queued += queue("reminder:event:" + save.userId() + ":" + save.eventId(), "event.reminder", now,
                    save.userId(), "Coming up: " + save.title(),
                    "Reminder: %s starts %s at %s, %s.".formatted(save.title(),
                            NotificationConsumer.when(save.startTime()), save.venue(), save.city()));
        }

        ZonedDateTime local = now.atZone(NotificationConsumer.DISPLAY_ZONE);
        if (!local.toLocalTime().isBefore(TRIP_REMINDERS_FROM)) {
            LocalDate tomorrow = local.toLocalDate().plusDays(1);
            for (Itinerary trip : itineraries.findByStartDate(tomorrow)) {
                queued += queue("reminder:itinerary:" + trip.getId(), "itinerary.reminder", now, trip.getUserId(),
                        "Tomorrow: your trip to " + trip.getDestination(), tripBody(trip));
            }
        }
        if (queued > 0) {
            log.info("Queued {} reminder(s)", queued);
        }
        return queued;
    }

    private int queue(String id, String type, Instant now, UUID userId, String subject, String body) {
        if (notifications.existsBySourceEventId(id)) {
            return 0;
        }
        publisher.publish(Topics.NOTIFICATIONS, userId.toString(), new NotificationRequest(id, type, now, userId, subject, body));
        return 1;
    }

    static String tripBody(Itinerary trip) {
        StringBuilder body = new StringBuilder("Your trip to %s starts tomorrow (%s).".formatted(
                trip.getDestination(), DAY.format(trip.getStartDate())));
        List<ItineraryPlan.Day> days = trip.getPlan().days();
        if (days.isEmpty() || days.get(0).stops().isEmpty()) {
            return body.append("\nOpen Wanderly to see your plan.").toString();
        }
        body.append(" Here's day 1:\n");
        ItineraryPlan.Day first = days.get(0);
        ItineraryPlan.Lunch lunch = first.lunch();
        for (int i = 0; i <= first.stops().size(); i++) {
            if (lunch != null && lunch.afterStops() == i) {
                body.append("\n%s–%s  Lunch%s".formatted(lunch.start(), lunch.end(),
                        lunch.name() == null ? "" : " at " + lunch.name()));
            }
            if (i < first.stops().size()) {
                ItineraryPlan.Stop stop = first.stops().get(i);
                body.append("\n%s–%s  %s".formatted(stop.arrive(), stop.leave(), stop.name()));
            }
        }
        if (days.size() > 1) {
            body.append("\n\n%d more day%s planned. Open Wanderly for the full itinerary.".formatted(
                    days.size() - 1, days.size() == 2 ? "" : "s"));
        }
        return body.append("\n\nHave a great trip!").toString();
    }
}
