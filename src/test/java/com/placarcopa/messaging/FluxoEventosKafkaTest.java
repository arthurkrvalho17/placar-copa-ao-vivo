package com.placarcopa.messaging;

import com.placarcopa.dataprovider.ApiFootballClient;
import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.dataprovider.dto.MatchEvent;
import com.placarcopa.domain.EstatisticaPartida;
import com.placarcopa.ingestion.IngestionService;
import com.placarcopa.stats.EstatisticaPartidaService;
import com.placarcopa.stats.PlacarAoVivo;
import com.placarcopa.stats.PlacarAoVivoService;
import com.placarcopa.stats.RedisConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Fluxo completo: ingestão → evento persistido → publicado no Kafka (após commit)
 * → consumido pelos DOIS consumer groups → banco de estatísticas e projeção
 * de consultas rápidas atualizados de forma independente.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class FluxoEventosKafkaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private PlacarAoVivoService placarAoVivoService;

    @Autowired
    private EstatisticaPartidaService estatisticaService;

    @Autowired
    private RedisMessageListenerContainer redisListenerContainer;

    @Autowired
    private Jackson2JsonRedisSerializer<PlacarAoVivo> placarSerializer;

    @MockitoBean
    private ApiFootballClient client;

    @Test
    void devePublicarEConsumirEventosNovosDeCadaJogo() {
        // Assinante extra no canal pub/sub, como o web server faz
        List<PlacarAoVivo> recebidosViaPubSub = new CopyOnWriteArrayList<>();
        redisListenerContainer.addMessageListener(
                (message, pattern) -> recebidosViaPubSub.add(placarSerializer.deserialize(message.getBody())),
                new ChannelTopic(RedisConfig.CANAL_ATUALIZACOES));

        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture("1H", 23, List.of(golDe("England", "Harry Kane", 22)))));

        ingestionService.ingerirAoVivo(null);

        // MATCH_STARTED (seq 1) + GOAL (seq 2) devem atravessar o Kafka
        PlacarAoVivo placar = aguardar(() ->
                placarAoVivoService.buscar("ENG-GAN-13-07-2026")
                        .filter(p -> p.ultimaSequence() >= 2));

        assertThat(placar.teamA()).isEqualTo("England");
        assertThat(placar.teamB()).isEqualTo("GANA");
        assertThat(placar.golsTeamA()).isEqualTo(1);
        assertThat(placar.golsTeamB()).isZero();
        assertThat(placar.status()).isEqualTo("MATCH_STARTED");

        // Segundo poll: empate, cartão e fim de jogo — apenas os eventos novos atravessam
        when(client.buscarJogosAoVivo()).thenReturn(List.of(
                fixture("FT", 90, List.of(
                        golDe("England", "Harry Kane", 22),
                        cartaoAmarelo("GANA", "Thomas Partey", 30),
                        golDe("GANA", "Mohammed Kudus", 75)))));

        ingestionService.ingerirAoVivo(null);

        // Consumer group de consultas rápidas (projeção em memória)
        PlacarAoVivo placarFinal = aguardar(() ->
                placarAoVivoService.buscar("ENG-GAN-13-07-2026")
                        .filter(p -> p.ultimaSequence() >= 7));

        assertThat(placarFinal.golsTeamA()).isEqualTo(1);
        assertThat(placarFinal.golsTeamB()).isEqualTo(1);
        assertThat(placarFinal.cartoesAmarelosTeamB()).isEqualTo(1);
        assertThat(placarFinal.status()).isEqualTo("MATCH_ENDED");

        // Consumer group de estatísticas (read model no banco)
        EstatisticaPartida estatistica = aguardarEstatistica(7);
        assertThat(estatistica.getGolsTeamA()).isEqualTo(1);
        assertThat(estatistica.getGolsTeamB()).isEqualTo(1);
        assertThat(estatistica.getCartoesAmarelosTeamB()).isEqualTo(1);
        assertThat(estatistica.getCartoesVermelhosTeamA()).isZero();
        assertThat(estatistica.getStatus()).isEqualTo("MATCH_ENDED");
        assertThat(estatistica.getCompetitionTitle()).isEqualTo("world-cup-2026");
        assertThat(estatistica.getCompetitionStage()).isEqualTo("groups_stage");

        // Canal pub/sub: o assinante (como o web server) recebeu cada atualização em tempo real
        aguardarPubSub(recebidosViaPubSub, 7);
    }

    private void aguardarPubSub(List<PlacarAoVivo> recebidos, int sequenceMinima) {
        Instant limite = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(limite)) {
            boolean chegou = recebidos.stream()
                    .anyMatch(p -> p != null && p.ultimaSequence() >= sequenceMinima
                            && "ENG-GAN-13-07-2026".equals(p.match()));
            if (chegou) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Atualização com sequence " + sequenceMinima + " não chegou pelo pub/sub em 15s");
    }

    private EstatisticaPartida aguardarEstatistica(int sequenceMinima) {
        Instant limite = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(limite)) {
            Optional<EstatisticaPartida> resultado = estatisticaService.buscar("ENG-GAN-13-07-2026")
                    .filter(e -> e.getUltimaSequence() >= sequenceMinima);
            if (resultado.isPresent()) {
                return resultado.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Estatística não chegou à sequence " + sequenceMinima + " em 15s");
    }

    private PlacarAoVivo aguardar(Supplier<Optional<PlacarAoVivo>> condicao) {
        Instant limite = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(limite)) {
            Optional<PlacarAoVivo> resultado = condicao.get();
            if (resultado.isPresent()) {
                return resultado.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condição não satisfeita em 15s — evento não chegou pelo Kafka");
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

    private MatchEvent cartaoAmarelo(String time, String jogador, int minuto) {
        return new MatchEvent(
                new MatchEvent.Time(minuto, null),
                new FixtureResponse.Team(2L, time, null, null),
                new MatchEvent.Player(200L, jogador),
                new MatchEvent.Player(null, null),
                "Card", "Yellow Card", null);
    }
}
