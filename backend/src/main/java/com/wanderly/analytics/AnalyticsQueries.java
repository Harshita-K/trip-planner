package com.wanderly.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL over the raw lake with DuckDB, the local stand-in for Athena: DuckDB reads the NDJSON files
 * straight from S3 (httpfs), uses the dt=/hour= folders as partition columns, and de-duplicates by
 * eventId. Each call opens a throwaway in-memory database; the lake is the only state.
 *
 * <p>{@code lakeRoot} is {@code s3://bucket} in the app and a local directory in tests.
 */
@Component
public class AnalyticsQueries {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueries.class);
    static final int DAYS = 30;

    public record Count(String key, long value) {
    }

    public record Day(String date, long events, long users) {
    }

    /** Users reaching <em>at least</em> each step (a planner also counts as engaged), so it's monotonic by construction. */
    public record Funnel(long active, long explored, long planned, long saved) {
    }

    public record Summary(boolean available, String message, String generatedAt, long totalEvents, long activeUsers,
                          List<Day> daily, List<Count> byType, List<Count> topCities, List<Count> topCategories,
                          List<Count> topRoutes, List<Count> topSaved, Funnel funnel, long lakeFiles) {

        static Summary unavailable(String message) {
            return new Summary(false, message, Instant.now().toString(), 0, 0, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), new Funnel(0, 0, 0, 0), 0);
        }
    }

    private final String lakeRoot;
    private final String s3Setup;

    @Autowired
    public AnalyticsQueries(@Value("${wanderly.analytics.lake.bucket}") String bucket,
                            @Value("${wanderly.analytics.lake.endpoint:}") String endpoint,
                            @Value("${wanderly.analytics.lake.region}") String region,
                            @Value("${wanderly.analytics.lake.access-key}") String accessKey,
                            @Value("${wanderly.analytics.lake.secret-key}") String secretKey) {
        this("s3://" + bucket, s3Secret(endpoint, region, accessKey, secretKey));
    }

    AnalyticsQueries(String lakeRoot, String s3Setup) {
        this.lakeRoot = lakeRoot;
        this.s3Setup = s3Setup;
    }

    private static String s3Secret(String endpoint, String region, String key, String secret) {
        if (endpoint.isBlank()) {
            return "CREATE SECRET lake (TYPE s3, PROVIDER credential_chain, REGION '%s')".formatted(region);
        }
        URI uri = URI.create(endpoint);
        return ("CREATE SECRET lake (TYPE s3, KEY_ID '%s', SECRET '%s', REGION '%s', ENDPOINT '%s', URL_STYLE 'path', "
                + "USE_SSL %s)").formatted(key, secret, region, uri.getAuthority(), "https".equals(uri.getScheme()));
    }

    public Summary summary() {
        String activity = lakeRoot + "/raw/user-activity/*/*/*.ndjson";
        String saves = lakeRoot + "/raw/saved-events/*/*/*.ndjson";
        String since = LocalDate.now(ZoneOffset.UTC).minusDays(DAYS).toString();
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement st = db.createStatement()) {
            if (lakeRoot.startsWith("s3://")) {
                st.execute("INSTALL httpfs");
                st.execute("LOAD httpfs");
                st.execute(s3Setup);
            }
            long files = scalar(st, "SELECT count(*) FROM glob('" + lakeRoot + "/raw/**/*.ndjson')");
            if (files == 0) {
                return Summary.unavailable("No events in the lake yet. Use the app (search, plan, save) and check back in a few seconds.");
            }
            // Partition pruning on dt (a folder name) before parsing; then one row per eventId.
            st.execute(("CREATE VIEW activity AS SELECT * EXCLUDE (rn) FROM (SELECT *, row_number() OVER (PARTITION BY eventId) rn "
                    + "FROM read_json_auto('%s', format = 'newline_delimited', hive_partitioning = true, union_by_name = true) "
                    + "WHERE dt >= '%s') WHERE rn = 1").formatted(activity, since));
            boolean hasSaves = scalar(st, "SELECT count(*) FROM glob('" + saves + "')") > 0;
            if (hasSaves) {
                st.execute(("CREATE VIEW saves AS SELECT * EXCLUDE (rn) FROM (SELECT *, row_number() OVER (PARTITION BY eventId) rn "
                        + "FROM read_json_auto('%s', format = 'newline_delimited', hive_partitioning = true, union_by_name = true) "
                        + "WHERE dt >= '%s') WHERE rn = 1").formatted(saves, since));
            }

            long total = scalar(st, "SELECT count(*) FROM activity");
            long users = scalar(st, "SELECT count(DISTINCT userId) FROM activity WHERE userId IS NOT NULL");
            List<Day> daily = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT CAST(dt AS VARCHAR), count(*), count(DISTINCT userId) FROM activity GROUP BY 1 ORDER BY 1")) {
                while (rs.next()) {
                    daily.add(new Day(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
            List<Count> byType = counts(st, "SELECT eventType, count(*) FROM activity GROUP BY 1 ORDER BY 2 DESC");
            List<Count> cities = counts(st, "SELECT city, count(*) FROM activity WHERE coalesce(city, '') <> '' GROUP BY 1 ORDER BY 2 DESC LIMIT 8");
            List<Count> categories = counts(st, "SELECT category, count(*) FROM activity WHERE coalesce(category, '') <> '' "
                    + "AND eventType <> 'hotels.searched' GROUP BY 1 ORDER BY 2 DESC LIMIT 8");
            List<Count> routes = counts(st, "SELECT itemId, count(*) FROM activity WHERE eventType = 'travel.searched' GROUP BY 1 ORDER BY 2 DESC LIMIT 5");
            // Net saves: an unsave cancels a save, so "most saved" reflects what people still plan to attend.
            List<Count> saved = hasSaves
                    ? counts(st, "SELECT itemTitle, sum(CASE eventType WHEN 'event.saved' THEN 1 ELSE -1 END) n FROM saves "
                    + "GROUP BY 1 HAVING n > 0 ORDER BY 2 DESC, 1 LIMIT 5")
                    : List.of();
            Funnel funnel;
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(DISTINCT userId),
                           count(DISTINCT userId) FILTER (WHERE eventType IN ('event.viewed', 'places.searched', 'travel.searched',
                                                                              'hotels.searched', 'itinerary.generated', 'event.saved')),
                           count(DISTINCT userId) FILTER (WHERE eventType IN ('itinerary.generated', 'event.saved')),
                           count(DISTINCT userId) FILTER (WHERE eventType = 'event.saved')
                    FROM activity WHERE userId IS NOT NULL""")) {
                rs.next();
                funnel = new Funnel(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
            }
            return new Summary(true, null, Instant.now().toString(), total, users, daily, byType, cities, categories, routes,
                    saved, funnel, files);
        } catch (SQLException e) {
            log.warn("Analytics query failed: {}", e.getMessage());
            return Summary.unavailable("The analytics lake can't be read right now (" + e.getMessage().lines().findFirst().orElse("") + ").");
        }
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static List<Count> counts(Statement st, String sql) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out.entrySet().stream().map(e -> new Count(e.getKey(), e.getValue())).toList();
    }
}
