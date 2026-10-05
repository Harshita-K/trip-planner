package com.wanderly.stay;

import com.wanderly.common.ApiException;
import com.wanderly.common.GeoPoint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** F9: where to stay near a destination, filtered by the user's budget, with indicative prices for the dates. */
@Service
public class HotelService {

    static final int MAX_RESULTS = 24;

    private final HotelSearch search;

    public HotelService(HotelSearch search) {
        this.search = search;
    }

    public HotelDtos.Stay near(String destination, GeoPoint center, LocalDate checkIn, LocalDate checkOut, int guests, String budget) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights < 1 || nights > 30) {
            throw ApiException.badRequest("Check-out must be 1 to 30 nights after check-in.");
        }
        List<HotelDtos.Listing> listings = search.near(center);
        if (listings == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Hotel search is unavailable right now. Please try again shortly.");
        }
        List<HotelDtos.Hotel> hotels = listings.stream()
                .map(l -> price(l, destination, center, checkIn, checkOut, (int) nights, guests, budget))
                .filter(h -> h.distanceKm() <= 8)
                .sorted(Comparator.comparing((HotelDtos.Hotel h) -> !h.matchesBudget()).thenComparingDouble(HotelDtos.Hotel::distanceKm))
                .limit(MAX_RESULTS)
                .toList();
        return new HotelDtos.Stay(destination, checkIn.toString(), checkOut.toString(), (int) nights, guests, budget, hotels,
                "Real places from OpenStreetMap; prices are indicative estimates, not live rates. Check availability with the property.");
    }

    static HotelDtos.Hotel price(HotelDtos.Listing l, String destination, GeoPoint center, LocalDate in, LocalDate out,
                                 int nights, int guests, String budget) {
        String tier = HotelPricing.tier(l.kind(), l.name());
        int total = HotelPricing.total(l.id(), l.kind(), tier, destination, in, out, guests);
        double km = Math.round(center.distanceKm(new GeoPoint(l.lat(), l.lng())) * 10) / 10.0;
        String osmUrl = l.id().startsWith("osm-") ? "https://www.openstreetmap.org/" + l.id().substring(4).replace('-', '/') : null;
        String maps = "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(l.name() + " " + (destination == null ? "" : destination), StandardCharsets.UTF_8);
        return new HotelDtos.Hotel(l.id(), l.name(), label(l.kind()), tier, l.lat(), l.lng(), km, l.street(),
                Math.round(total / (float) nights / HotelPricing.rooms(l.kind(), guests) / 50) * 50, total, nights,
                HotelPricing.rooms(l.kind(), guests), HotelPricing.fits(tier, budget), true, osmUrl, maps);
    }

    private static String label(String kind) {
        return switch (kind) {
            case "guest_house" -> "Guest house";
            case "hostel" -> "Hostel";
            case "motel" -> "Motel";
            default -> "Hotel";
        };
    }

    static String normaliseBudget(String budget) {
        String b = budget == null ? "" : budget.trim().toLowerCase(Locale.ROOT);
        return b.equals("low") || b.equals("high") ? b : "mid";
    }
}
