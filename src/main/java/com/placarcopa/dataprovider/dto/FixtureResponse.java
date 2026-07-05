package com.placarcopa.dataprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Um item da resposta de {@code GET /fixtures}.
 * Nas consultas ao vivo ({@code live=...}) e por id, a lista de eventos vem preenchida.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FixtureResponse(
        Fixture fixture,
        League league,
        Teams teams,
        Goals goals,
        Score score,
        List<MatchEvent> events
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fixture(
            Long id,
            String referee,
            String timezone,
            OffsetDateTime date,
            Long timestamp,
            Venue venue,
            Status status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            @JsonProperty("long") String descricao,
            @JsonProperty("short") String codigo,
            Integer elapsed,
            Integer extra
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Venue(Long id, String name, String city) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record League(
            Long id,
            String name,
            String country,
            String logo,
            String flag,
            Integer season,
            String round
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Teams(Team home, Team away) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Team(Long id, String name, String logo, Boolean winner) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Goals(Integer home, Integer away) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Score(Goals halftime, Goals fulltime, Goals extratime, Goals penalty) {
    }
}
