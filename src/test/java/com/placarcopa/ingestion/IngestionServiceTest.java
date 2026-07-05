package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.ApiFootballClient;
import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.dataprovider.dto.MatchEvent;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import com.placarcopa.repository.EventoPartidaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Roda contra um PostgreSQL real (Testcontainers) — o mesmo banco de produção.
 * Requer Docker em execução.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({IngestionService.class, FixtureMapper.class})
class IngestionServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private IngestionService service;

    @Autowired
    private EventoPartidaRepository eventoRepository;

    @MockitoBean
    private ApiFootballClient client;

    @Test
    void deveGerarEventosSemDuplicarEComSequenceIncremental() {
        // 1º poll: jogo no primeiro tempo com um gol
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture("1H", 23, List.of(golDe("England", "Harry Kane", 22)))));

        IngestionSummary primeira = service.ingerirAoVivo(null);

        assertThat(primeira.fixturesRecebidos()).isEqualTo(1);
        assertThat(primeira.eventosNovos()).isEqualTo(2); // MATCH_STARTED + GOAL

        List<EventoPartida> eventos = eventoRepository.findByMatchCodeOrderBySequenceAsc("ENG-GAN-13-07-2026");
        assertThat(eventos).extracting(EventoPartida::getEvent)
                .containsExactly(TipoEvento.MATCH_STARTED, TipoEvento.GOAL);
        assertThat(eventos).extracting(EventoPartida::getSequence).containsExactly(1, 2);

        EventoPartida inicio = eventos.get(0);
        assertThat(inicio.getMatchCode()).isEqualTo("ENG-GAN-13-07-2026");
        assertThat(inicio.getTeamA()).isEqualTo("England");
        assertThat(inicio.getTeamB()).isEqualTo("GANA");
        assertThat(inicio.getCompetitionTitle()).isEqualTo("world-cup-2026");
        assertThat(inicio.getCompetitionStage()).isEqualTo("groups_stage");
        assertThat(inicio.getMinute()).isZero();
        assertThat(inicio.getPayload()).isEmpty();

        EventoPartida gol = eventos.get(1);
        assertThat(gol.getPayload())
                .containsEntry("team", "England")
                .containsEntry("player", "Harry Kane");

        // 2º poll: jogo encerrado com um segundo gol — só o que é novo entra
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture("FT", 90, List.of(
                        golDe("England", "Harry Kane", 22),
                        golDe("GANA", "Mohammed Kudus", 75)))));

        IngestionSummary segunda = service.ingerirAoVivo(null);

        // HALF_TIME + SECOND_HALF_STARTED + GOAL(75) + MATCH_ENDED
        assertThat(segunda.eventosNovos()).isEqualTo(4);

        List<EventoPartida> todos = eventoRepository.findByMatchCodeOrderBySequenceAsc("ENG-GAN-13-07-2026");
        assertThat(todos).hasSize(6);
        assertThat(todos).extracting(EventoPartida::getSequence).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(todos).extracting(EventoPartida::getEvent).containsExactly(
                TipoEvento.MATCH_STARTED,
                TipoEvento.GOAL,
                TipoEvento.HALF_TIME,
                TipoEvento.SECOND_HALF_STARTED,
                TipoEvento.GOAL,
                TipoEvento.MATCH_ENDED);

        // 3º poll idêntico: nada novo
        IngestionSummary terceira = service.ingerirAoVivo(null);
        assertThat(terceira.eventosNovos()).isZero();
        assertThat(eventoRepository.count()).isEqualTo(6);
    }

    private FixtureResponse fixture(String statusCodigo, int minuto, List<MatchEvent> eventos) {
        return new FixtureResponse(
                new FixtureResponse.Fixture(12010L, null, "UTC",
                        OffsetDateTime.parse("2026-07-13T16:00:00Z"), 1784044800L,
                        new FixtureResponse.Venue(10L, "MetLife Stadium", "New Jersey"),
                        new FixtureResponse.Status("First Half", statusCodigo, minuto, null)),
                new FixtureResponse.League(1L, "World Cup", "World", null, null, 2026, "Group A - 1"),
                new FixtureResponse.Teams(
                        new FixtureResponse.Team(10L, "England", null, null),
                        new FixtureResponse.Team(1504L, "GANA", null, null)),
                new FixtureResponse.Goals(null, null),
                null,
                eventos);
    }

    private MatchEvent golDe(String time, String jogador, int minuto) {
        return new MatchEvent(
                new MatchEvent.Time(minuto, null),
                new FixtureResponse.Team(1L, time, null, null),
                new MatchEvent.Player(100L, jogador),
                new MatchEvent.Player(null, null),
                "Goal", "Normal Goal", null);
    }
}
