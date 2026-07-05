package com.placarcopa.stats;

import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlacarServiceTest {

    private final PlacarService service = new PlacarService();

    @Test
    void deveCalcularPlacarApenasComEventos() {
        List<EventoPartida> eventos = List.of(
                evento(TipoEvento.MATCH_STARTED, 0, 1, Map.of()),
                evento(TipoEvento.GOAL, 22, 2, Map.of("team", "England", "player", "Harry Kane")),
                evento(TipoEvento.PENALTY_GOAL, 40, 3, Map.of("team", "GANA", "player", "Mohammed Kudus")),
                evento(TipoEvento.OWN_GOAL, 60, 4, Map.of("team", "GANA", "player", "Zagueiro")),
                evento(TipoEvento.MISSED_PENALTY, 70, 5, Map.of("team", "England", "player", "Harry Kane")),
                evento(TipoEvento.YELLOW_CARD, 80, 6, Map.of("team", "England", "player", "Rice")),
                evento(TipoEvento.MATCH_ENDED, 90, 7, Map.of()));

        PlacarService.Placar placar = service.calcular(eventos);

        // Gol normal England + gol contra de GANA = 2; pênalti convertido GANA = 1
        assertThat(placar.teamA()).isEqualTo("England");
        assertThat(placar.teamB()).isEqualTo("GANA");
        assertThat(placar.golsTeamA()).isEqualTo(2);
        assertThat(placar.golsTeamB()).isEqualTo(1);
    }

    private EventoPartida evento(TipoEvento tipo, int minuto, int sequence, Map<String, Object> payload) {
        return new EventoPartida("ENG-GAN-13-07-2026", "England", "GANA",
                "world-cup-2026", "groups_stage", tipo, minuto, sequence, payload, 12010L);
    }
}
