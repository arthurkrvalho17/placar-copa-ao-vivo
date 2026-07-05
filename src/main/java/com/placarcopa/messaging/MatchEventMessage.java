package com.placarcopa.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;

import java.util.Map;

/**
 * Mensagem de evento de partida no formato acordado para o Kafka:
 *
 * <pre>
 * {
 *   "id": "12010",
 *   "match": "ENG-GAN-13-07-2026",
 *   "team_A": "England",
 *   "team_B": "GANA",
 *   "competition": {"title": "world-cup-2026", "stage": "groups_stage"},
 *   "event": "MATCH_STARTED",
 *   "minute": "0",
 *   "sequence": 1,
 *   "payload": {}
 * }
 * </pre>
 */
public record MatchEventMessage(
        String id,
        String match,
        @JsonProperty("team_A") String teamA,
        @JsonProperty("team_B") String teamB,
        Competition competition,
        TipoEvento event,
        String minute,
        int sequence,
        Map<String, Object> payload
) {

    public record Competition(String title, String stage) {
    }

    public static MatchEventMessage de(EventoPartida evento) {
        return new MatchEventMessage(
                String.valueOf(evento.getId()),
                evento.getMatchCode(),
                evento.getTeamA(),
                evento.getTeamB(),
                new Competition(evento.getCompetitionTitle(), evento.getCompetitionStage()),
                evento.getEvent(),
                evento.getMinute() == null ? null : String.valueOf(evento.getMinute()),
                evento.getSequence(),
                evento.getPayload()
        );
    }
}
