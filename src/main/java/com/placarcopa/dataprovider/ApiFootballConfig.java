package com.placarcopa.dataprovider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ApiFootballProperties.class)
public class ApiFootballConfig {

    @Bean
    public RestClient apiFootballRestClient(RestClient.Builder builder, ApiFootballProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        return builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(ApiFootballClient.HEADER_API_KEY, properties.key())
                .requestFactory(requestFactory)
                .build();
    }
}
