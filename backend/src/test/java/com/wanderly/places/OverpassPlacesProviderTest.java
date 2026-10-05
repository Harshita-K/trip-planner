package com.wanderly.places;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Overpass `out center tags` response shape (nodes have lat/lon; ways/relations have center). */
class OverpassPlacesProviderTest {

    private static final GeoPoint UDAIPUR = new GeoPoint(24.5787, 73.6863);

    private static final String RESPONSE = """
            {"elements":[
              {"type":"way","id":101,"center":{"lat":24.5764,"lon":73.6835},
               "tags":{"tourism":"museum","historic":"palace","name":"सिटी पैलेस","name:en":"City Palace",
                       "wikidata":"Q1094421","heritage":"2","opening_hours":"Mo-Su 09:30-17:30","website":"https://example"}},
              {"type":"node","id":202,"lat":24.5794,"lon":73.6830,
               "tags":{"amenity":"place_of_worship","historic":"temple","name":"Jagdish Temple","wikidata":"Q6122987",
                       "opening_hours":"Mo-Su 05:00-14:00,17:00-22:00"}},
              {"type":"way","id":303,"center":{"lat":24.5720,"lon":73.6790},
               "tags":{"natural":"water","leisure":"park","name":"Gulab Bagh","wikidata":"Q5617980"}},
              {"type":"node","id":404,"lat":24.5810,"lon":73.6900,
               "tags":{"tourism":"museum","name":"Vintage Car Museum","opening_hours":"Tu-Su 09:00-21:00; Mo off"}},
              {"type":"node","id":505,"lat":24.5811,"lon":73.6901,
               "tags":{"tourism":"museum","name":"Vintage Car Museum"}},
              {"type":"node","id":606,"lat":24.5800,"lon":73.6800,"tags":{"tourism":"attraction"}},
              {"type":"node","id":707,"lat":24.5790,"lon":73.6870,
               "tags":{"tourism":"viewpoint","name":"Karni Mata Ropeway Viewpoint","opening_hours":"sunrise-sunset"}}
            ]}
            """;

    private List<Place> parse(Set<String> wanted) throws Exception {
        return OverpassPlacesProvider.parse(new ObjectMapper().readTree(RESPONSE), UDAIPUR, wanted);
    }

    @Test
    void mapsCategoriesNamesHoursAndNotability() throws Exception {
        List<Place> places = parse(Set.of("museum", "history", "religious", "nature", "landmark"));

        assertThat(places).extracting(Place::name)
                .containsExactly("City Palace", "Jagdish Temple", "Gulab Bagh", "Vintage Car Museum", "Karni Mata Ropeway Viewpoint");

        Place palace = places.get(0);
        assertThat(palace.id()).isEqualTo("osm-way-101");
        assertThat(palace.category()).isEqualTo("museum");                 // museum wins over historic
        assertThat(palace.hoursSource()).isEqualTo(Place.OSM);
        assertThat(palace.opens()).isEqualTo(LocalTime.of(9, 30));
        assertThat(palace.rating()).isEqualTo(4.7);                         // wiki + heritage + tourism + hours + website

        Place temple = places.get(1);
        assertThat(temple.category()).isEqualTo("religious");              // place_of_worship wins over historic
        assertThat(temple.opens()).isEqualTo(LocalTime.of(5, 0));
        assertThat(temple.closes()).isEqualTo(LocalTime.of(22, 0));

        Place carMuseum = places.get(3);
        assertThat(carMuseum.closedOn()).containsExactly(DayOfWeek.MONDAY);
        assertThat(carMuseum.rating()).isEqualTo(3.6);                      // no wiki link: modest score

        Place viewpoint = places.get(4);
        assertThat(viewpoint.category()).isEqualTo("nature");
        assertThat(viewpoint.hoursSource()).isEqualTo(Place.ESTIMATED);    // "sunrise-sunset" isn't guessed
    }

    @Test
    void bundledPageViewsOutrankTagBasedNotability() throws Exception {
        ObjectMapper json = new ObjectMapper();
        double taj = OverpassPlacesProvider.notability(json.readTree("{\"wikidata\":\"Q9141\",\"wanderly:views30\":\"117872\"}"));
        double statue = OverpassPlacesProvider.notability(json.readTree("{\"wikidata\":\"Q1\",\"wanderly:views30\":\"40\"}"));
        double noViews = OverpassPlacesProvider.notability(json.readTree("{\"wikidata\":\"Q2\"}"));
        assertThat(taj).isGreaterThan(noViews).isGreaterThan(statue);
        assertThat(taj).isEqualTo(4.8);
    }

    @Test
    void onlyRequestedCategoriesAreReturned() throws Exception {
        assertThat(parse(Set.of("religious"))).extracting(Place::name).containsExactly("Jagdish Temple");
    }

    @Test
    void queryOnlyIncludesFoodWhenAsked() {
        String sightseeing = OverpassPlacesProvider.query(UDAIPUR, 5, Set.of("museum", "nature"));
        assertThat(sightseeing).contains("\"tourism\"=\"museum\"", "[bbox:24.53365,73.63677,24.62375,73.73583]", "out center tags")
                .doesNotContain("restaurant");
        assertThat(OverpassPlacesProvider.query(UDAIPUR, 2, Set.of("food"))).contains("restaurant|cafe");
        assertThat(OverpassPlacesProvider.query(UDAIPUR, 2, Set.of("unknown"))).isNull();
    }

    @Test
    void overpassOutageSurfacesAsUnavailable() {
        OverpassClient client = mock(OverpassClient.class);
        when(client.query(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new OverpassPlacesProvider(client).nearby(UDAIPUR, 5, Set.of()))
                .hasMessageContaining("unavailable");
    }
}
