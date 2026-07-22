package com.placarcopa.stats;

import com.placarcopa.domain.TipoEvento;
import com.placarcopa.messaging.MatchEventMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

class PlacarAoVivoTest {

    @Test
    void deveProjetarPlacarAPartirDosEventos() {
        PlacarAoVivo placar = PlacarAoVivo.inicial(mensagem(TipoEvento.MATCH_STARTED, "0", 1, Map.of()))
                .aplicar(mensagem(TipoEvento.MATCH_STARTED, "0", 1, Map.of()))
                .aplicar(mensagem(TipoEvento.GOAL, "22", 2, Map.of("team", "England")))
                // gol contra na API-Football: team = time BENEFICIADO
                .aplicar(mensagem(TipoEvento.OWN_GOAL, "40", 3, Map.of("team", "England")))
                .aplicar(mensagem(TipoEvento.YELLOW_CARD, "55", 4, Map.of("team", "GANA")))
                .aplicar(mensagem(TipoEvento.RED_CARD, "70", 5, Map.of("team", "England")))
                // VAR anulou o gol do England dos 22'
                .aplicar(mensagem(TipoEvento.GOAL_CANCELLED, "22", 6, Map.of("team", "England")))
                .aplicar(mensagem(TipoEvento.MATCH_ENDED, "90", 7, Map.of()));

        assertThat(placar.golsTeamA()).isEqualTo(1);  // +GOAL +OWN_GOAL beneficiado -GOAL_CANCELLED
        assertThat(placar.golsTeamB()).isZero();
        assertThat(placar.cartoesAmarelosTeamB()).isEqualTo(1);
        assertThat(placar.cartoesVermelhosTeamA()).isEqualTo(1);
        assertThat(placar.status()).isEqualTo("MATCH_ENDED");
        assertThat(placar.minute()).isEqualTo("90");
        assertThat(placar.ultimaSequence()).isEqualTo(7);
    }

    @Test
    void naoDeveReaplicarMensagemDuplicada() {
        PlacarAoVivo aposGol = PlacarAoVivo.inicial(mensagem(TipoEvento.MATCH_STARTED, "0", 1, Map.of()))
                .aplicar(mensagem(TipoEvento.MATCH_STARTED, "0", 1, Map.of()))
                .aplicar(mensagem(TipoEvento.GOAL, "22", 2, Map.of("team", "England")));

        // redelivery da mesma mensagem (at-least-once): mesma instância, sem mudança
        PlacarAoVivo reaplicado = aposGol.aplicar(mensagem(TipoEvento.GOAL, "22", 2, Map.of("team", "England")));

        assertThatObject(reaplicado).isSameAs(aposGol);
        assertThat(reaplicado.golsTeamA()).isEqualTo(1);
    }

    private MatchEventMessage mensagem(TipoEvento tipo, String minuto, int sequence, Map<String, Object> payload) {
        return new MatchEventMessage("1", "ENG-GAN-13-07-2026", "England", "GANA",
                new MatchEventMessage.Competition("world-cup-2026", "groups_stage"),
                tipo, minuto, sequence, payload);
    }
}
