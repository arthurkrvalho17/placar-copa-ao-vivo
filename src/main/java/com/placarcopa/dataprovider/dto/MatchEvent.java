package com.placarcopa.dataprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Evento de uma partida ({@code GET /fixtures/events} ou embutido em {@code /fixtures?live=...}).
 * Tipos possíveis: Goal (Normal Goal, Own Goal, Penalty, Missed Penalty),
 * Card (Yellow Card, Red card), Subst e Var.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchEvent(
        Time time,
        FixtureResponse.Team team,
        Player player,
        Player assist,
        String type,
        String detail,
        String comments
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Time(Integer elapsed, Integer extra) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(Long id, String name) {
    }
}
