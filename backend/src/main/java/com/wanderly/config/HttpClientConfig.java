package com.wanderly.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Timeouts for every outbound HTTP client (OpenTripMap, Overpass, Nominatim): never hang a request thread. */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer outboundTimeouts() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(30_000);   // Overpass queries can take several seconds
            builder.requestFactory(factory);
        };
    }
}
