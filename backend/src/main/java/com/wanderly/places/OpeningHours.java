package com.wanderly.places;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the common subset of OpenStreetMap's {@code opening_hours} syntax, reduced to what the
 * planner uses: one typical daily window plus the weekly closing days.
 *
 * <p>Supported: {@code 24/7}; rules separated by {@code ;} where later rules override earlier ones
 * (OSM semantics); weekday lists and ranges ({@code Mo-Fr}, {@code Sa,Su}, wrap-around {@code Fr-Mo});
 * several time ranges ({@code 09:00-12:00,14:00-18:00}, collapsed to first open/last close);
 * overnight ranges ({@code 17:00-04:00}, closes at midnight for planning); open end ({@code 18:00+});
 * {@code off}/{@code closed}. Holiday tokens (PH, SH) are ignored. Anything else, such as month or
 * week selectors, makes the parse fail so the caller can fall back to an estimate rather than guess.
 */
public final class OpeningHours {

    public record Weekly(LocalTime opens, LocalTime closes, Set<DayOfWeek> closedOn) {
    }

    private static final List<String> DAY_CODES = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");
    private static final Pattern DAYS = Pattern.compile("^((?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?(?:\\s*,\\s*(?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?)*)\\b\\s*(.*)$");
    private static final Pattern RANGE = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\+?$|^(\\d{1,2}):(\\d{2})\\+$");
    private static final int END_OF_DAY = 23 * 60 + 59;

    private OpeningHours() {
    }

    public static Optional<Weekly> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.equals("24/7")) {
            return Optional.of(new Weekly(LocalTime.MIDNIGHT, LocalTime.of(23, 59), Set.of()));
        }
        Map<DayOfWeek, int[]> week = new EnumMap<>(DayOfWeek.class);   // day -> {open, close}, absent = closed
        boolean anyRule = false;
        for (String part : value.split(";|\\|\\|")) {
            String rule = part.trim();
            if (rule.isEmpty()) {
                continue;
            }
            Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
            Matcher dm = DAYS.matcher(rule);
            if (dm.matches()) {
                days = parseDays(dm.group(1));
                rule = dm.group(2).trim();
                if (days.isEmpty()) {
                    continue;   // only holiday tokens: irrelevant for weekly planning
                }
            }
            if (rule.equalsIgnoreCase("off") || rule.equalsIgnoreCase("closed")) {
                days.forEach(week::remove);
                anyRule = true;
                continue;
            }
            Optional<int[]> window = parseTimes(rule);
            if (window.isEmpty()) {
                return Optional.empty();   // unsupported syntax: let the caller estimate
            }
            days.forEach(d -> week.put(d, window.get()));
            anyRule = true;
        }
        if (!anyRule || week.isEmpty()) {
            return Optional.empty();
        }

        // Typical window = the one shared by the most open days (ties: earliest open).
        Map<String, Integer> counts = new HashMap<>();
        week.values().forEach(w -> counts.merge(w[0] + "-" + w[1], 1, Integer::sum));
        int[] typical = week.values().stream()
                .max((a, b) -> {
                    int c = Integer.compare(counts.get(a[0] + "-" + a[1]), counts.get(b[0] + "-" + b[1]));
                    return c != 0 ? c : Integer.compare(b[0], a[0]);
                })
                .orElseThrow();
        Set<DayOfWeek> closed = EnumSet.allOf(DayOfWeek.class);
        closed.removeAll(week.keySet());
        return Optional.of(new Weekly(time(typical[0]), time(typical[1]), closed));
    }

    private static Set<DayOfWeek> parseDays(String spec) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : spec.split(",")) {
            String t = token.trim();
            if (t.equals("PH") || t.equals("SH")) {
                continue;
            }
            String[] ends = t.split("-");
            int from = DAY_CODES.indexOf(ends[0]);
            int to = ends.length > 1 ? DAY_CODES.indexOf(ends[1]) : from;
            for (int i = from; ; i = (i + 1) % 7) {   // supports wrap-around like Fr-Mo
                days.add(DayOfWeek.of(i + 1));
                if (i == to) {
                    break;
                }
            }
        }
        return days;
    }

    private static Optional<int[]> parseTimes(String spec) {
        int open = Integer.MAX_VALUE;
        int close = -1;
        for (String range : spec.split(",")) {
            Matcher m = RANGE.matcher(range.trim());
            if (!m.matches()) {
                return Optional.empty();
            }
            int start;
            int end;
            if (m.group(1) != null) {
                start = minutes(m.group(1), m.group(2));
                end = minutes(m.group(3), m.group(4));
                if (end <= start || end > END_OF_DAY) {
                    end = END_OF_DAY;   // overnight or "24:00": treat as open until midnight
                }
            } else {
                start = minutes(m.group(5), m.group(6));
                end = END_OF_DAY;      // "18:00+" = open end
            }
            if (start > END_OF_DAY) {
                return Optional.empty();
            }
            open = Math.min(open, start);
            close = Math.max(close, end);
        }
        return close < 0 ? Optional.empty() : Optional.of(new int[]{open, close});
    }

    private static int minutes(String h, String m) {
        return Integer.parseInt(h) * 60 + Integer.parseInt(m);
    }

    private static LocalTime time(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60);
    }
}
