package com.wanderly.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real DuckDB SQL against a local lake directory laid out exactly like the S3 one. */
class AnalyticsQueriesTest {

    @TempDir
    Path lake;

    private void file(String topic, String key, String... lines) throws Exception {
        Path p = lake.resolve(key.replace("{topic}", topic));
        Files.createDirectories(p.getParent());
        Files.writeString(p, String.join("\n", lines) + "\n");
    }

    private static String activity(String id, String type, String user, String city, String category, String item) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-06T10:00:00Z","userId":%s,"itemType":"x","itemId":%s,"category":%s,"city":%s}"""
                .formatted(id, type, user == null ? "null" : "\"" + user + "\"", item == null ? "null" : "\"" + item + "\"",
                        category == null ? "null" : "\"" + category + "\"", city == null ? "null" : "\"" + city + "\"");
    }

    @Test
    void aggregatesDeduplicatesAndBuildsTheFunnel() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String key = LakeWriter.objectKey("{topic}", 0, 0, Instant.parse(today + "T10:00:00Z"));
        assertThat(key).isEqualTo("raw/{topic}/dt=" + today + "/hour=10/part-0-0000000000000000000.ndjson");

        file("user-activity", key,
                activity("e1", "places.searched", "u1", null, "museum", null),
                activity("e2", "itinerary.generated", "u1", "Udaipur", "history", "i1"),
                activity("e3", "travel.searched", "u2", "Udaipur", null, "Delhi → Udaipur"),
                activity("e4", "event.saved", "u1", "Bengaluru", "music", "b1"),
                activity("e5", "event.viewed", null, "Jaipur", "music", "x"));
        // A redelivered batch landed in a second file: e2 again must not be double-counted.
        file("user-activity", key.replace("part-0-", "part-1-"), activity("e2", "itinerary.generated", "u1", "Udaipur", "history", "i1"));
        // Heritage Walk saved; Comedy Night saved then unsaved, so it nets out of "most saved".
        file("saved-events", key, """
                {"eventId":"s-e1","eventType":"event.saved","itemTitle":"Heritage Walk","userId":"u1"}
                {"eventId":"s-e2","eventType":"event.saved","itemTitle":"Comedy Night","userId":"u2"}
                {"eventId":"s-e3","eventType":"event.unsaved","itemTitle":"Comedy Night","userId":"u2"}""");

        AnalyticsQueries.Summary s = new AnalyticsQueries(lake.toString(), null).summary();

        assertThat(s.available()).isTrue();
        assertThat(s.totalEvents()).isEqualTo(5);                  // 6 lines, e2 de-duplicated
        assertThat(s.activeUsers()).isEqualTo(2);
        assertThat(s.topCities()).first().satisfies(c -> {
            assertThat(c.key()).isEqualTo("Udaipur");
            assertThat(c.value()).isEqualTo(2);
        });
        assertThat(s.topRoutes()).extracting(AnalyticsQueries.Count::key).containsExactly("Delhi → Udaipur");
        assertThat(s.topSaved()).extracting(AnalyticsQueries.Count::key).containsExactly("Heritage Walk");
        assertThat(s.funnel()).isEqualTo(new AnalyticsQueries.Funnel(2, 2, 1, 1));
        assertThat(s.funnel().explored()).isGreaterThanOrEqualTo(s.funnel().planned());   // monotonic
        assertThat(s.funnel().planned()).isGreaterThanOrEqualTo(s.funnel().saved());
        assertThat(s.daily()).hasSize(1);
        assertThat(s.lakeFiles()).isEqualTo(3);
    }

    @Test
    void emptyLakeIsReportedNotAnError() {
        AnalyticsQueries.Summary s = new AnalyticsQueries(lake.toString(), null).summary();
        assertThat(s.available()).isFalse();
        assertThat(s.message()).contains("No events");
    }
}
