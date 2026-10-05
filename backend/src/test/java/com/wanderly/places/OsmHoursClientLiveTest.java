package com.wanderly.places;

import com.wanderly.config.WanderlyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Calls the real public Overpass API. Opt-in so normal builds never depend on the internet:
 * {@code LIVE_OSM=true mvn test -Dtest=OsmHoursClientLiveTest}
 */
@EnabledIfEnvironmentVariable(named = "LIVE_OSM", matches = "true")
class OsmHoursClientLiveTest {

    @Test
    @SuppressWarnings("unchecked")
    void fetchesRealOpeningHoursAndTheyParse() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(null);   // cache miss -> real call
        WanderlyProperties props = new WanderlyProperties(null, null, new WanderlyProperties.Places(Duration.ofHours(1), true,
                null, new WanderlyProperties.Osm(System.getenv().getOrDefault("OVERPASS_URL", "https://overpass-api.de/api/interpreter"), null, null, "live test")));

        // Real OSM objects in Kala Ghoda, Mumbai (ids looked up via Nominatim).
        List<String> refs = List.of("node/669259659", "way/206808196", "way/40387597", "node/3366405791");

        Map<String, String> hours = new OsmHoursClient(new OverpassClient(RestClient.builder(), props), redis).hoursFor(refs);

        assertThat(hours).as("no Overpass server answered (they are often busy; retry later)").isNotEmpty();
        hours.forEach((ref, raw) -> System.out.printf("%s -> %s -> %s%n", ref, raw, OpeningHours.parse(raw).orElse(null)));
        long parsed = hours.values().stream().filter(raw -> OpeningHours.parse(raw).isPresent()).count();
        assertThat(parsed).as("most real-world values parse").isGreaterThanOrEqualTo(hours.size() / 2);
    }
}
