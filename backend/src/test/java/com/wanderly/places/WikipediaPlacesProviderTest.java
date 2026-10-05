package com.wanderly.places;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shape of a real answer for Chittorgarh (generator=geosearch, formatversion=2), trimmed. */
class WikipediaPlacesProviderTest {

    private static final GeoPoint CHITTORGARH = new GeoPoint(24.8887, 74.6269);

    private static final String RESPONSE = """
            {"query":{"pages":[
              {"pageid":1,"title":"Chittorgarh","description":"City in Rajasthan, India",
               "coordinates":[{"lat":24.88,"lon":74.62}],"pageprops":{"wikibase_item":"Q41827"},"pageviews":{"a":5000,"b":2810}},
              {"pageid":2,"title":"Vijaya Stambha","description":"Victory monument within Chittor Fort in Chittorgarh",
               "coordinates":[{"lat":24.8873,"lon":74.6459}],"pageprops":{"wikibase_item":"Q2724452"},"pageviews":{"a":900,"b":758}},
              {"pageid":3,"title":"Chittorgarh Lok Sabha constituency","description":"Lok Sabha constituency in Rajasthan, India",
               "coordinates":[{"lat":24.88,"lon":74.62}],"pageviews":{"a":1070}},
              {"pageid":4,"title":"Chittaurgarh Junction railway station","description":"Railway station in Rajasthan, India",
               "coordinates":[{"lat":24.87,"lon":74.62}],"pageviews":{"a":408}},
              {"pageid":5,"title":"Kalika Mata Temple, Chittorgarh Fort","description":"Hindu temple in India",
               "coordinates":[{"lat":24.8842,"lon":74.6462}],"pageprops":{"wikibase_item":"Q15233008"},"pageviews":{"a":259,"b":null}},
              {"pageid":6,"title":"Bhojunda Stromatolite Park",
               "coordinates":[{"lat":24.85,"lon":74.60}],"pageprops":{"wikibase_item":"Q56064078"},"pageviews":{"a":34}},
              {"pageid":7,"title":"Some Article Without Coordinates","description":"Fort in Rajasthan"}
            ]}}
            """;

    private List<Place> parse(Set<String> wanted) throws Exception {
        return WikipediaPlacesProvider.parse(new ObjectMapper().readTree(RESPONSE), CHITTORGARH, wanted);
    }

    @Test
    void keepsSightsDropsCitiesConstituenciesAndStations() throws Exception {
        List<Place> places = parse(Set.of("history", "religious", "nature", "museum", "landmark"));

        assertThat(places).extracting(Place::name)
                .containsExactly("Vijaya Stambha", "Kalika Mata Temple", "Bhojunda Stromatolite Park");
        assertThat(places).extracting(Place::category).containsExactly("history", "religious", "nature");
        assertThat(places.get(0).id()).isEqualTo("wiki-Q2724452");
        assertThat(places.get(0).hoursSource()).isEqualTo(Place.ESTIMATED);
        assertThat(places.get(1).name()).doesNotContain(",");   // "…, Chittorgarh Fort" disambiguator stripped
    }

    @Test
    void pageViewsDrivePopularity() throws Exception {
        List<Place> places = parse(Set.of("history", "religious", "nature"));
        double stambha = places.get(0).rating();   // 1,658 views
        double temple = places.get(1).rating();    // 259 views
        double park = places.get(2).rating();      // 34 views
        assertThat(stambha).isGreaterThan(temple).isGreaterThan(park);
        assertThat(WikipediaPlacesProvider.popularity(0)).isEqualTo(3.2);
        assertThat(WikipediaPlacesProvider.popularity(10_000_000)).isEqualTo(4.8);
    }

    @Test
    void classification() {
        assertThat(WikipediaPlacesProvider.classify("Palace in Udaipur, Rajasthan", "City Palace, Udaipur")).isEqualTo("history");
        assertThat(WikipediaPlacesProvider.classify("Museum in Mumbai", "CSMVS")).isEqualTo("museum");
        assertThat(WikipediaPlacesProvider.classify("Lake in Rajasthan, India", "Fateh Sagar Lake")).isEqualTo("nature");
        assertThat(WikipediaPlacesProvider.classify("Medical college in Udaipur", "RNT Medical College")).isNull();
        assertThat(WikipediaPlacesProvider.classify("Palace on an island in Lake Pichola", "Jag Mandir")).isEqualTo("history");
        assertThat(WikipediaPlacesProvider.classify("", "Bhojunda Stromatolite Park")).isEqualTo("nature");
        assertThat(WikipediaPlacesProvider.classify("Town in Punjab, India", "Zira")).isNull();
        assertThat(WikipediaPlacesProvider.displayName("Chandpole (Udaipur)")).isEqualTo("Chandpole");
    }

    @Test
    void wikipediaPlacesGetOsmHoursViaWikidata() {
        OsmHoursClient client = mock(OsmHoursClient.class);
        when(client.cachedOrRefreshInBackground(any(), any(), anyString()))
                .thenReturn(new OsmHoursClient.Lookup(Map.of(), Map.of("Q2724452", "Mo-Su 09:30-17:00")));
        Place stambha = new Place("wiki-Q2724452", "Vijaya Stambha", "history", 24.8873, 74.6459, 4.3, 75,
                LocalTime.of(9, 0), LocalTime.of(17, 30), null, 0, Set.of(), Place.ESTIMATED);

        List<Place> result = new OsmHoursEnricher(client).enrich(List.of(stambha), false);

        assertThat(result.get(0).hoursSource()).isEqualTo(Place.OSM);
        assertThat(result.get(0).opens()).isEqualTo(LocalTime.of(9, 30));
    }
}
