package com.wanderly.places;

import com.wanderly.common.GeoPoint;
import com.wanderly.config.WanderlyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** OpenTripMap response shape per https://opentripmap.io/docs (places/radius, format=json). */
class OpenTripMapPlacesProviderTest {

    private static final String RESPONSE = """
            [
              {"xid":"W1","name":"Chhatrapati Shivaji Maharaj Vastu Sangrahalaya","dist":310.2,"rate":3,"osm":"way/1",
               "kinds":"cultural,museums,interesting_places","point":{"lon":72.8326,"lat":18.9269}},
              {"xid":"N2","name":"Gateway of India","dist":900.5,"rate":"3h","osm":"node/2",
               "kinds":"historic,monuments_and_memorials,interesting_places","point":{"lon":72.8347,"lat":18.9220}},
              {"xid":"N3","name":"Gateway of India","dist":905.0,"rate":2,"osm":"node/3",
               "kinds":"historic,interesting_places","point":{"lon":72.8348,"lat":18.9221}},
              {"xid":"N4","name":"","dist":50,"rate":1,"osm":"node/4","kinds":"foods","point":{"lon":72.83,"lat":18.93}},
              {"xid":"N5","name":"Cafe Mondegar","dist":700,"rate":1,"osm":"node/5",
               "kinds":"foods,cafes","point":{"lon":72.8316,"lat":18.9276}}
            ]
            """;

    @Test
    void mapsCategoriesDedupesAndUsesStableOsmIds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example/places/radius")))
                .andExpect(queryParam("apikey", "test-key"))
                .andExpect(queryParam("format", "json"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        WanderlyProperties props = new WanderlyProperties(null, null, new WanderlyProperties.Places(
                Duration.ofHours(1), true, new WanderlyProperties.OpenTripMap("test-key", "https://api.example"),
                new WanderlyProperties.Osm("https://overpass.example", "https://photon.example", "https://wiki.example", "")));

        List<Place> places = new OpenTripMapPlacesProvider(builder, props)
                .nearby(new GeoPoint(18.9300, 72.8330), 2, Set.of());
        server.verify();

        assertThat(places).extracting(Place::name)
                .containsExactly("Chhatrapati Shivaji Maharaj Vastu Sangrahalaya", "Gateway of India", "Cafe Mondegar");

        Place museum = places.get(0);
        assertThat(museum.id()).isEqualTo("osm-way-1");
        assertThat(museum.category()).isEqualTo("museum");
        assertThat(museum.hoursSource()).isEqualTo(Place.ESTIMATED);   // real hours are added by OsmHoursEnricher
        assertThat(museum.opens()).isEqualTo(LocalTime.of(10, 0));     // museum default

        Place gateway = places.get(1);
        assertThat(gateway.category()).isEqualTo("history");
        assertThat(gateway.rating()).isEqualTo(4.95);           // rate "3h"

        assertThat(places.get(2).category()).isEqualTo("food");
    }

    @Test
    void enricherAppliesRealHoursWhereOsmHasThem() {
        OsmHoursClient client = mock(OsmHoursClient.class);
        when(client.cachedOrRefreshInBackground(any(), any(), any()))
                .thenReturn(new OsmHoursClient.Lookup(Map.of("way/1", "Tu-Su 10:15-18:00; Mo off"), Map.of()));
        Place withHours = new Place("osm-way-1", "Museum", "museum", 0, 0, 4.5, 90, LocalTime.of(10, 0),
                LocalTime.of(17, 0), null, 0, Set.of(), Place.ESTIMATED);
        Place without = new Place("osm-node-2", "Gate", "history", 0, 0, 4.5, 60, LocalTime.of(9, 0),
                LocalTime.of(17, 30), null, 0, Set.of(), Place.ESTIMATED);
        Place curated = new Place("blr-cubbon", "Cubbon Park", "nature", 0, 0, 4.5, 60, LocalTime.of(6, 0),
                LocalTime.of(18, 0), "Bengaluru", 0);

        List<Place> result = new OsmHoursEnricher(client).enrich(List.of(withHours, without, curated), true);

        assertThat(result.get(0).hoursSource()).isEqualTo(Place.OSM);
        assertThat(result.get(0).opens()).isEqualTo(LocalTime.of(10, 15));
        assertThat(result.get(0).closedOn()).containsExactly(DayOfWeek.MONDAY);
        assertThat(result.get(1).hoursSource()).isEqualTo(Place.ESTIMATED);
        assertThat(result.get(2)).isSameAs(curated);
        assertThat(OsmHoursEnricher.osmRef("osm-relation-77")).isEqualTo("relation/77");
        assertThat(OsmHoursEnricher.osmRef("blr-cubbon")).isNull();
    }
}
