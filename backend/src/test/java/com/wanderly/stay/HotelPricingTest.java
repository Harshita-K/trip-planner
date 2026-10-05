package com.wanderly.stay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotelPricingTest {

    @Test
    void tiersFromKindAndName() {
        assertThat(HotelPricing.tier("hotel", "Hotel Kumbha Palace")).isEqualTo(HotelPricing.COMFORT);    // "Palace" is common, not a signal
        assertThat(HotelPricing.tier("guest_house", "Palace View Guest House")).isEqualTo(HotelPricing.BUDGET);
        assertThat(HotelPricing.tier("hotel", "Laxmi Vilas Hotel (The Lalit)")).isEqualTo(HotelPricing.LUXURY);
        assertThat(HotelPricing.tier("hotel", "The Leela Palace")).isEqualTo(HotelPricing.LUXURY);
        assertThat(HotelPricing.tier("hostel", "Zostel Udaipur")).isEqualTo(HotelPricing.BUDGET);
        assertThat(HotelPricing.tier("hotel", "Shanti Guest House")).isEqualTo(HotelPricing.BUDGET);
        assertThat(HotelPricing.tier("hotel", "Hotel Lake Star")).isEqualTo(HotelPricing.COMFORT);
    }

    @Test
    void weekendsPeakSeasonAndMetrosCostMore() {
        LocalDate tueOffPeak = LocalDate.of(2026, 5, 12);
        LocalDate friPeak = LocalDate.of(2026, 11, 13);
        int weekday = HotelPricing.total("osm-node-1", "hotel", HotelPricing.COMFORT, "Bhopal", tueOffPeak, tueOffPeak.plusDays(1), 2);
        int weekendPeak = HotelPricing.total("osm-node-1", "hotel", HotelPricing.COMFORT, "Bhopal", friPeak, friPeak.plusDays(1), 2);
        int metro = HotelPricing.total("osm-node-1", "hotel", HotelPricing.COMFORT, "Mumbai", tueOffPeak, tueOffPeak.plusDays(1), 2);
        assertThat(weekendPeak).isGreaterThan(weekday);
        assertThat(metro).isGreaterThan(weekday);
    }

    @Test
    void roomsAndTotalsScaleWithGuestsAndNights() {
        LocalDate in = LocalDate.of(2026, 5, 12);
        int oneNight = HotelPricing.total("osm-node-9", "hotel", HotelPricing.LUXURY, "", in, in.plusDays(1), 2);
        int threeNights = HotelPricing.total("osm-node-9", "hotel", HotelPricing.LUXURY, "", in, in.plusDays(3), 2);
        int fourGuests = HotelPricing.total("osm-node-9", "hotel", HotelPricing.LUXURY, "", in, in.plusDays(1), 4);
        assertThat(threeNights).isBetween(oneNight * 3 - 100, oneNight * 3 + 100);
        assertThat(fourGuests).isBetween(oneNight * 2 - 100, oneNight * 2 + 100);   // 2 rooms
        assertThat(HotelPricing.rooms("hostel", 3)).isEqualTo(3);
    }

    @Test
    void budgetPreferenceSortsMatchingStaysFirst() throws Exception {
        String photon = """
                {"features":[
                  {"geometry":{"coordinates":[73.6835,24.5764]},"properties":{"osm_type":"N","osm_id":1,"osm_key":"tourism","osm_value":"hotel","name":"Hotel Kumbha Palace","street":"Lake Road"}},
                  {"geometry":{"coordinates":[73.6800,24.5790]},"properties":{"osm_type":"W","osm_id":2,"osm_key":"tourism","osm_value":"hostel","name":"Zostel Udaipur"}},
                  {"geometry":{"coordinates":[73.6900,24.5800]},"properties":{"osm_type":"N","osm_id":3,"osm_key":"tourism","osm_value":"hotel","name":"Hotel Lake Star"}},
                  {"geometry":{"coordinates":[73.6900,24.5800]},"properties":{"osm_type":"N","osm_id":4,"osm_key":"amenity","osm_value":"restaurant","name":"Not a hotel"}}
                ]}""";
        List<HotelDtos.Listing> listings = HotelSearch.parse(new ObjectMapper().readTree(photon));
        assertThat(listings).extracting(HotelDtos.Listing::name).containsExactly("Hotel Kumbha Palace", "Zostel Udaipur", "Hotel Lake Star");
        assertThat(listings.get(1).id()).isEqualTo("osm-way-2");

        GeoPoint udaipur = new GeoPoint(24.5787, 73.6863);
        LocalDate in = LocalDate.of(2026, 11, 10);
        List<HotelDtos.Hotel> hotels = listings.stream()
                .map(l -> HotelService.price(l, "Udaipur", udaipur, in, in.plusDays(2), 2, 2, "low")).toList();
        assertThat(hotels.stream().filter(HotelDtos.Hotel::matchesBudget)).extracting(HotelDtos.Hotel::name).containsExactly("Zostel Udaipur");
        assertThat(hotels.get(0).tier()).isEqualTo(HotelPricing.COMFORT);
        assertThat(hotels.get(1).osmUrl()).isEqualTo("https://www.openstreetmap.org/way/2");
        assertThat(hotels).allSatisfy(h -> assertThat(h.priceEstimated()).isTrue());
    }
}
