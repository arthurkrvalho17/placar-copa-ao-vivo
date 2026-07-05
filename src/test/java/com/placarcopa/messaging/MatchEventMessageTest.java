package com.placarcopa.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.placarcopa.domain.TipoEvento;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MatchEventMessageTest {

    @Test
    void deveSerializarNoFormatoAcordadoParaOKafka() throws Exception {
        MatchEventMessage mensagem = new MatchEventMessage(
                "12010",
                "ENG-GAN-13-07-2026",
                "England",
                "GANA",
                new MatchEventMessage.Competition("world-cup-2026", "groups_stage"),
                TipoEvento.MATCH_STARTED,
                "0",
                1,
                Map.of());

        String json = new ObjectMapper().writeValueAsString(mensagem);

        assertThat(json).contains("\"id\":\"12010\"");
        assertThat(json).contains("\"match\":\"ENG-GAN-13-07-2026\"");
        assertThat(json).contains("\"team_A\":\"England\"");
        assertThat(json).contains("\"team_B\":\"GANA\"");
        assertThat(json).contains("\"title\":\"world-cup-2026\"");
        assertThat(json).contains("\"stage\":\"groups_stage\"");
        assertThat(json).contains("\"event\":\"MATCH_STARTED\"");
        assertThat(json).contains("\"minute\":\"0\"");
        assertThat(json).contains("\"sequence\":1");
        assertThat(json).contains("\"payload\":{}");
    }
}
