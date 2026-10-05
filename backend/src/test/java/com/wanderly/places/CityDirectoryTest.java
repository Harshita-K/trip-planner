package com.wanderly.places;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanderly.common.JsonCache;
import com.wanderly.config.WanderlyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CityDirectoryTest {

    /** Photon GeoJSON shape, trimmed from a real answer for "zir" (it includes a Bangladeshi village). */
    private static final String PHOTON = """
            {"type":"FeatureCollection","features":[
              {"geometry":{"coordinates":[90.2537,23.9933],"type":"Point"},"properties":{"name":"Zirani","state":"Dhaka Division","countrycode":"BD","osm_key":"place","osm_value":"village"}},
              {"geometry":{"coordinates":[84.2534,26.2189],"type":"Point"},"properties":{"name":"Ziradei","state":"Bihar","countrycode":"IN","osm_key":"place","osm_value":"village"}},
              {"geometry":{"coordinates":[76.8200,30.6557],"type":"Point"},"properties":{"name":"Zirakpur","state":"Punjab","countrycode":"IN","osm_key":"place","osm_value":"town"}},
              {"geometry":{"coordinates":[93.8153,27.5386],"type":"Point"},"properties":{"name":"Ziro","state":"Arunachal Pradesh","countrycode":"IN","osm_key":"place","osm_value":"city"}},
              {"geometry":{"coordinates":[93.8154,27.5387],"type":"Point"},"properties":{"name":"Ziro","state":"Arunachal Pradesh","countrycode":"IN","osm_key":"place","osm_value":"city"}}
            ]}
            """;

    private MockRestServiceServer server;
    private JsonCache cache;
    private CityDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        cache = mock(JsonCache.class);
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        PlacesService places = mock(PlacesService.class);
        WanderlyProperties props = new WanderlyProperties(null, null, new WanderlyProperties.Places(Duration.ofHours(1), true,
                null, new WanderlyProperties.Osm("https://overpass.example", "https://photon.example", "https://wiki.example", "")));
        directory = new CityDirectory(new ObjectMapper(), places, cache, builder, props);
    }

    @Test
    void photonResultsAreIndiaOnlyRankedCityTownVillageAndDeduped() {
        server.expect(requestTo(containsString("https://photon.example/api/?q=zir")))
                .andRespond(withSuccess(PHOTON, MediaType.APPLICATION_JSON));

        List<CityDirectory.City> cities = directory.suggest("Zir");

        server.verify();
        assertThat(cities).extracting(CityDirectory.City::name).containsExactly("Ziro", "Zirakpur", "Ziradei");
        assertThat(cities.get(0).region()).isEqualTo("Arunachal Pradesh");
        assertThat(cities.get(0).source()).isEqualTo("photon");
        verify(cache).put(any(), any(), any());   // successful answers are cached
    }

    @Test
    void photonFailureReturnsNothingAndIsNotCached() {
        server.expect(requestTo(containsString("q=zir"))).andRespond(withServerError());

        assertThat(directory.suggest("zir")).isEmpty();
        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    void tooShortQueriesNeverCallPhoton() {
        assertThat(directory.suggest("z")).isEmpty();
        server.verify();   // no requests expected
    }

    @Test
    void instantSearchUnderstandsOldNamesAndStates() {
        assertThat(directory.search("pondi", 5)).extracting(CityDirectory.City::name).first().isEqualTo("Puducherry");
        assertThat(directory.search("bombay", 5)).extracting(CityDirectory.City::name).first().isEqualTo("Mumbai");
        assertThat(directory.search("raj", 10)).extracting(CityDirectory.City::name).contains("Jaipur", "Udaipur");
        assertThat(directory.search("ud", 5)).extracting(CityDirectory.City::name).first().isEqualTo("Udaipur");   // real name beats the Udhagamandalam alias
        assertThat(directory.search("", 3)).extracting(CityDirectory.City::name).containsExactly("Bengaluru", "Jaipur", "Goa");
    }
}
