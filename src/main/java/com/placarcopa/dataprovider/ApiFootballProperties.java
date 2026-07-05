package com.placarcopa.dataprovider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-football")
public record ApiFootballProperties(
        String baseUrl,
        String key
) {
}
