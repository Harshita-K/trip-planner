package com.wanderly.stay;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tiers and indicative nightly prices (F9). OpenStreetMap knows <em>what</em> a place is (hotel,
 * guest house, hostel) and its name, but not its rates, and live rate feeds are paid partner APIs,
 * so prices are estimated and labelled as such. Pure and deterministic.
 *
 * <ul>
 *   <li>Tier: hostels and guest houses are budget (whatever their name); resort/heritage/spa or a
 *       luxury chain name is luxury; other hotels are comfort. "Palace" alone is not a luxury signal.</li>
 *   <li>Base per room-night: budget ₹1,400 (hostel bed ₹700), comfort ₹3,200, luxury ₹9,500, ±25%
 *       per property (stable hash of its id), x1.3 in metros, x1.15 in premium leisure cities.</li>
 *   <li>Per night: +15% Friday/Saturday nights, +15% in peak season (October–March).</li>
 *   <li>Rooms: one per 2 guests (hostels: one bed per guest).</li>
 * </ul>
 */
public final class HotelPricing {

    public static final String BUDGET = "budget";
    public static final String COMFORT = "comfort";
    public static final String LUXURY = "luxury";

    /**
     * Strong luxury signals only. "Palace" is deliberately absent: in India it's common in ordinary
     * hotel names (Hotel Rani Palace, Palace View Guest House), so it says little about price.
     */
    private static final Pattern LUXURY_NAME = Pattern.compile("\\b(resort|heritage|spa|taj|oberoi|leela|marriott|hyatt|"
            + "radisson|itc|trident|westin|sheraton|hilton|vivanta|lalit|fairmont|jw|ritz|four seasons|st\\.? regis)\\b");
    private static final Pattern BUDGET_NAME = Pattern.compile("\\b(hostel|guest ?house|homestay|home stay|lodge|inn|dormitory|backpackers?|zostel)\\b");
    private static final Map<String, Integer> BASE = Map.of(BUDGET, 1400, COMFORT, 3200, LUXURY, 9500);
    private static final Set<String> METROS = Set.of("mumbai", "delhi", "new delhi", "bengaluru", "bangalore", "hyderabad",
            "chennai", "kolkata", "pune", "gurugram", "noida");
    private static final Set<String> PREMIUM_LEISURE = Set.of("goa", "udaipur", "jaipur", "shimla", "manali", "leh",
            "munnar", "ooty", "darjeeling", "rishikesh", "mussoorie", "nainital", "kochi", "agra", "varkala", "kovalam");

    private HotelPricing() {
    }

    public static String tier(String kind, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        // The OSM type is the most reliable signal: a guest house is budget whatever it's called.
        if (kind.equals("hostel") || kind.equals("guest_house") || BUDGET_NAME.matcher(n).find()) {
            return BUDGET;
        }
        if (LUXURY_NAME.matcher(n).find()) {
            return LUXURY;
        }
        return COMFORT;
    }

    /** Rooms needed: one per 2 guests; a hostel bed per guest. */
    public static int rooms(String kind, int guests) {
        return kind.equals("hostel") ? guests : (int) Math.ceil(guests / 2.0);
    }

    /** Total for all nights and rooms, priced night by night. */
    public static int total(String id, String kind, String tier, String city, LocalDate checkIn, LocalDate checkOut, int guests) {
        double base = kind.equals("hostel") ? 700 : BASE.get(tier);
        double variation = 0.75 + (Math.floorMod(id.hashCode(), 1000) / 1000.0) * 0.5;   // stable ±25% per property
        String c = city == null ? "" : city.toLowerCase(Locale.ROOT);
        double location = METROS.contains(c) ? 1.3 : PREMIUM_LEISURE.contains(c) ? 1.15 : 1.0;
        double sum = 0;
        for (LocalDate night = checkIn; night.isBefore(checkOut); night = night.plusDays(1)) {
            DayOfWeek dow = night.getDayOfWeek();
            double weekend = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY ? 1.15 : 1.0;
            double season = isPeak(night.getMonth()) ? 1.15 : 1.0;
            sum += base * variation * location * weekend * season;
        }
        return (int) (Math.round(sum * rooms(kind, guests) / 50.0) * 50);
    }

    /** Which tiers fit a user's budget preference (low | mid | high). */
    public static boolean fits(String tier, String budget) {
        return switch (budget == null ? "mid" : budget) {
            case "low" -> tier.equals(BUDGET);
            case "high" -> tier.equals(LUXURY);
            default -> tier.equals(COMFORT);
        };
    }

    private static boolean isPeak(Month m) {
        return m.getValue() >= 10 || m.getValue() <= 3;
    }
}
