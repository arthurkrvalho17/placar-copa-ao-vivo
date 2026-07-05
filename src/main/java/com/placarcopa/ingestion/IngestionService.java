package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.ApiFootballClient;
import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.ingestion.FixtureMapper.EventoProposto;
import com.placarcopa.messaging.EventoPartidaCriado;
import com.placarcopa.repository.EventoPartidaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ingestão orientada a eventos (append-only): a cada poll do data provider,
 * compara o estado do fixture com os eventos já gravados e acrescenta apenas
 * os novos, com sequence incremental por partida. Nunca atualiza nem apaga —
 * placar e estatísticas são derivados da sequência de eventos.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ApiFootballClient client;
    private final EventoPartidaRepository eventoRepository;
    private final FixtureMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    public IngestionService(ApiFootballClient client,
                            EventoPartidaRepository eventoRepository,
                            FixtureMapper mapper,
                            ApplicationEventPublisher eventPublisher) {
        this.client = client;
        this.eventoRepository = eventoRepository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
    }

    /** Ingere os jogos em andamento; sem ligas informadas, busca todos. */
    @Transactional
    public IngestionSummary ingerirAoVivo(List<Long> ligaIds) {
        List<FixtureResponse> fixtures = (ligaIds == null || ligaIds.isEmpty())
                ? client.buscarJogosAoVivo()
                : client.buscarJogosAoVivoPorLigas(ligaIds);
        return ingerir(fixtures);
    }

    /** Ingere uma competição/temporada inteira (ex.: Copa do Mundo 2026). */
    @Transactional
    public IngestionSummary ingerirCompeticao(long ligaId, int temporada) {
        return ingerir(client.buscarJogosPorLigaETemporada(ligaId, temporada));
    }

    private IngestionSummary ingerir(List<FixtureResponse> fixtures) {
        int eventosNovos = 0;
        for (FixtureResponse fixture : fixtures) {
            if (fixture.fixture() == null || fixture.fixture().id() == null
                    || fixture.teams() == null || fixture.teams().home() == null || fixture.teams().away() == null) {
                log.warn("Fixture ignorado por falta de dados essenciais: {}", fixture);
                continue;
            }
            eventosNovos += ingerirFixture(fixture);
        }

        IngestionSummary resumo = new IngestionSummary(fixtures.size(), eventosNovos);
        log.info("Ingestao concluida: {} fixtures recebidos, {} eventos novos",
                resumo.fixturesRecebidos(), resumo.eventosNovos());
        return resumo;
    }

    private int ingerirFixture(FixtureResponse fixture) {
        Long apiFixtureId = fixture.fixture().id();
        List<EventoPartida> existentes = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(apiFixtureId);

        Set<String> chavesExistentes = new HashSet<>();
        int ultimaSequence = 0;
        for (EventoPartida existente : existentes) {
            chavesExistentes.add(EventoProposto.chave(
                    existente.getEvent(), existente.getMinute(), existente.getPayload()));
            ultimaSequence = Math.max(ultimaSequence, existente.getSequence());
        }

        String matchCode = mapper.matchCode(fixture);
        String competitionTitle = mapper.competitionTitle(fixture);
        String competitionStage = mapper.competitionStage(fixture);
        String teamA = fixture.teams().home().name();
        String teamB = fixture.teams().away().name();

        int novos = 0;
        for (EventoProposto proposto : mapper.extrairEventos(fixture)) {
            if (!chavesExistentes.add(proposto.chaveDeduplicacao())) {
                continue;
            }
            EventoPartida salvo = eventoRepository.save(new EventoPartida(
                    matchCode, teamA, teamB, competitionTitle, competitionStage,
                    proposto.event(), proposto.minute(), ++ultimaSequence,
                    proposto.payload(), apiFixtureId));
            // Publicado no Kafka pelo MatchEventRelay somente após o commit
            eventPublisher.publishEvent(new EventoPartidaCriado(salvo));
            novos++;
        }
        return novos;
    }
}
