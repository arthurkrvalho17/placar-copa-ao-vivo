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
                fixture(12010L, "England", "GANA", "1H", 23,
                        List.of(golDe("England", "Harry Kane", 22)))));

        IngestionSummary primeira = service.ingerirAoVivo(null);

        assertThat(primeira.fixturesRecebidos()).isEqualTo(1);
        assertThat(primeira.eventosNovos()).isEqualTo(2); // MATCH_STARTED + GOAL

        List<EventoPartida> eventos = eventoRepository.findByMatchCodeOrderBySequenceAsc("ENG-GAN-13-07-2026");
        assertThat(eventos).extracting(EventoPartida::getEvent)
                .containsExactly(TipoEvento.MATCH_STARTED, TipoEvento.GOAL);
        assertThat(eventos).extracting(EventoPartida::getSequence).containsExactly(1, 2);

        EventoPartida inicio = eventos.get(0);
        assertThat(inicio.getTeamA()).isEqualTo("England");
        assertThat(inicio.getTeamB()).isEqualTo("GANA");
        assertThat(inicio.getCompetitionTitle()).isEqualTo("world-cup-2026");
        assertThat(inicio.getCompetitionStage()).isEqualTo("groups_stage");

        // 2º poll: jogo encerrado com um segundo gol — só o que é novo entra
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(12010L, "England", "GANA", "FT", 90, List.of(
                        golDe("England", "Harry Kane", 22),
                        golDe("GANA", "Mohammed Kudus", 75)))));

        IngestionSummary segunda = service.ingerirAoVivo(null);

        // HALF_TIME + SECOND_HALF_STARTED + GOAL(75) + MATCH_ENDED
        assertThat(segunda.eventosNovos()).isEqualTo(4);

        List<EventoPartida> todos = eventoRepository.findByMatchCodeOrderBySequenceAsc("ENG-GAN-13-07-2026");
        assertThat(todos).hasSize(6);
        assertThat(todos).extracting(EventoPartida::getSequence).containsExactly(1, 2, 3, 4, 5, 6);

        // 3º poll idêntico: nada novo
        assertThat(service.ingerirAoVivo(null).eventosNovos()).isZero();
    }

    @Test
    void deveIngerirDoisGolsIdenticosDoMesmoJogadorNoMesmoMinuto() {
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(555L, "Brasil", "Chile", "2H", 91, List.of(
                        golDe("Brasil", "Richarlison", 90),
                        golDe("Brasil", "Richarlison", 90)))));

        IngestionSummary resumo = service.ingerirAoVivo(null);

        // MATCH_STARTED + HALF_TIME + SECOND_HALF_STARTED + 2 gols
        assertThat(resumo.eventosNovos()).isEqualTo(5);
        long gols = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(555L).stream()
                .filter(e -> e.getEvent() == TipoEvento.GOAL)
                .count();
        assertThat(gols).isEqualTo(2);

        // reingestão idêntica não duplica
        assertThat(service.ingerirAoVivo(null).eventosNovos()).isZero();
    }

    @Test
    void deveResolverColisaoDeMatchCodeEntreFixturesDiferentes() {
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(111L, "Barcelona", "Real Madrid", "1H", 10, List.of()),
                fixture(222L, "Barinas", "Real Pilar", "1H", 12, List.of())));

        service.ingerirAoVivo(null);

        List<EventoPartida> doPrimeiro = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(111L);
        List<EventoPartida> doSegundo = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(222L);

        assertThat(doPrimeiro).isNotEmpty();
        assertThat(doSegundo).isNotEmpty();
        assertThat(doPrimeiro.get(0).getMatchCode()).isEqualTo("BAR-REA-13-07-2026");
        // colisão detectada: o segundo fixture ganha o sufixo do id da API
        assertThat(doSegundo.get(0).getMatchCode()).isEqualTo("BAR-REA-13-07-2026-222");

        // o código atribuído é reutilizado nos polls seguintes
        // (o fixture 111 sumiu do feed: a reconciliação vai buscá-lo por id)
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(222L, "Barinas", "Real Pilar", "HT", 45, List.of())));
        when(client.buscarJogoPorId(111L)).thenReturn(List.of(
                fixture(111L, "Barcelona", "Real Madrid", "1H", 30, List.of())));
        service.ingerirAoVivo(null);
        assertThat(eventoRepository.findByMatchCodeOrderBySequenceAsc("BAR-REA-13-07-2026-222"))
                .extracting(EventoPartida::getEvent)
                .containsExactly(TipoEvento.MATCH_STARTED, TipoEvento.HALF_TIME);
    }

    @Test
    void deveEncerrarPartidaQueSumiuDoFeedAoVivo() {
        // 1º poll: jogo em andamento
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(888L, "Franca", "Italia", "1H", 30,
                        List.of(golDe("Franca", "Mbappe", 12)))));
        service.ingerirAoVivo(null);

        // 2º poll: o jogo terminou entre os polls e SAIU do feed live=all;
        // a reconciliação busca por id e ingere o desfecho
        when(client.buscarJogosAoVivo()).thenReturn(List.of());
        when(client.buscarJogoPorId(888L)).thenReturn(List.of(
                fixture(888L, "Franca", "Italia", "FT", 90,
                        List.of(golDe("Franca", "Mbappe", 12)))));

        IngestionSummary resumo = service.ingerirAoVivo(null);

        assertThat(resumo.eventosNovos()).isEqualTo(3); // HALF_TIME + SECOND_HALF + MATCH_ENDED
        List<EventoPartida> eventos = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(888L);
        assertThat(eventos).extracting(EventoPartida::getEvent).containsExactly(
                TipoEvento.MATCH_STARTED,
                TipoEvento.GOAL,
                TipoEvento.HALF_TIME,
                TipoEvento.SECOND_HALF_STARTED,
                TipoEvento.MATCH_ENDED);

        // 3º poll: partida já encerrada não é mais reconciliada (nenhuma busca por id)
        when(client.buscarJogosAoVivo()).thenReturn(List.of());
        assertThat(service.ingerirAoVivo(null).eventosNovos()).isZero();
    }

    @Test
    void deveCompensarGolAnuladoPeloVar() {
        // 1º poll: gol do Kane registrado
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(777L, "England", "GANA", "1H", 23,
                        List.of(golDe("England", "Harry Kane", 22)))));
        service.ingerirAoVivo(null);

        // 2º poll: a API removeu o gol da timeline E há decisão de VAR anulando
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture(777L, "England", "GANA", "1H", 26,
                        List.of(varDecisao("England", "Goal cancelled", 24)))));
        service.ingerirAoVivo(null);

        List<EventoPartida> eventos = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(777L);
        assertThat(eventos).extracting(EventoPartida::getEvent).containsExactly(
                TipoEvento.MATCH_STARTED,
                TipoEvento.GOAL,
                TipoEvento.VAR_DECISION,
                TipoEvento.GOAL_CANCELLED);
        EventoPartida compensacao = eventos.get(3);
        assertThat(compensacao.getPayload()).containsEntry("team", "England");
        assertThat(compensacao.getPayload()).containsEntry("cancelled_type", "GOAL");

        // 3º poll idêntico: a compensação não é emitida de novo
        assertThat(service.ingerirAoVivo(null).eventosNovos()).isZero();
    }

    private FixtureResponse fixture(long apiId, String teamA, String teamB,
                                    String statusCodigo, int minuto, List<MatchEvent> eventos) {
        return new FixtureResponse(
                new FixtureResponse.Fixture(apiId, null, "UTC",
                        OffsetDateTime.parse("2026-07-13T16:00:00Z"), 1784044800L,
                        new FixtureResponse.Venue(10L, "MetLife Stadium", "New Jersey"),
                        new FixtureResponse.Status("First Half", statusCodigo, minuto, null)),
                new FixtureResponse.League(1L, "World Cup", "World", null, null, 2026, "Group A - 1"),
                new FixtureResponse.Teams(
                        new FixtureResponse.Team(10L, teamA, null, null),
                        new FixtureResponse.Team(1504L, teamB, null, null)),
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

    private MatchEvent varDecisao(String time, String detalhe, int minuto) {
        return new MatchEvent(
                new MatchEvent.Time(minuto, null),
                new FixtureResponse.Team(1L, time, null, null),
                new MatchEvent.Player(null, null),
                new MatchEvent.Player(null, null),
                "Var", detalhe, null);
    }
}
