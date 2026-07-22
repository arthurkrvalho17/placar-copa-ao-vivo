package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.ApiFootballClient;
import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import com.placarcopa.ingestion.FixtureMapper.EventoProposto;
import com.placarcopa.messaging.EventoPartidaCriado;
import com.placarcopa.repository.EventoPartidaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ingestão orientada a eventos (append-only): a cada poll do data provider,
 * compara o estado do fixture com os eventos já gravados e acrescenta apenas
 * os novos, com sequence incremental por partida. Nunca atualiza nem apaga —
 * gols anulados pelo VAR geram um evento de compensação (GOAL_CANCELLED).
 *
 * As execuções são serializadas por um lock: a atribuição de sequence lê o
 * máximo atual em memória e não é segura sob concorrência. Para o lock valer,
 * cada fixture é persistido numa transação própria (TransactionTemplate) que
 * COMMITA dentro do lock — um @Transactional no método público commitaria
 * depois do unlock e reabriria a corrida. Isso também mantém as chamadas HTTP
 * ao provider fora de qualquer transação, sem prender conexão do pool.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final Set<TipoEvento> TIPOS_DE_GOL =
            Set.of(TipoEvento.GOAL, TipoEvento.PENALTY_GOAL, TipoEvento.OWN_GOAL);

    private final ApiFootballClient client;
    private final EventoPartidaRepository eventoRepository;
    private final FixtureMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transacao;
    private final ReentrantLock trava = new ReentrantLock();

    public IngestionService(ApiFootballClient client,
                            EventoPartidaRepository eventoRepository,
                            FixtureMapper mapper,
                            ApplicationEventPublisher eventPublisher,
                            TransactionTemplate transacao) {
        this.client = client;
        this.eventoRepository = eventoRepository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.transacao = transacao;
    }

    private static final Set<TipoEvento> EVENTOS_TERMINAIS =
            Set.of(TipoEvento.MATCH_ENDED, TipoEvento.MATCH_CANCELLED, TipoEvento.MATCH_POSTPONED);

    /**
     * Ingere os jogos em andamento (sem ligas informadas, todos) e depois
     * reconcilia: partidas em aberto no nosso store que saíram do feed ao vivo
     * (terminaram entre polls) são buscadas por id para ingerir o desfecho —
     * sem isso elas ficariam "ao vivo" para sempre no placar.
     */
    public IngestionSummary ingerirAoVivo(List<Long> ligaIds) {
        trava.lock();
        try {
            List<FixtureResponse> fixtures = (ligaIds == null || ligaIds.isEmpty())
                    ? client.buscarJogosAoVivo()
                    : client.buscarJogosAoVivoPorLigas(ligaIds);
            int eventosNovos = ingerirLista(fixtures);
            eventosNovos += encerrarPartidasForaDoFeed(fixtures);
            return concluir(fixtures.size(), eventosNovos);
        } finally {
            trava.unlock();
        }
    }

    /** Ingere uma competição/temporada inteira (ex.: Copa do Mundo 2026). */
    public IngestionSummary ingerirCompeticao(long ligaId, int temporada) {
        trava.lock();
        try {
            List<FixtureResponse> fixtures = client.buscarJogosPorLigaETemporada(ligaId, temporada);
            return concluir(fixtures.size(), ingerirLista(fixtures));
        } finally {
            trava.unlock();
        }
    }

    private IngestionSummary concluir(int fixturesRecebidos, int eventosNovos) {
        IngestionSummary resumo = new IngestionSummary(fixturesRecebidos, eventosNovos);
        log.info("Ingestao concluida: {} fixtures recebidos, {} eventos novos",
                resumo.fixturesRecebidos(), resumo.eventosNovos());
        return resumo;
    }

    private int ingerirLista(List<FixtureResponse> fixtures) {
        int eventosNovos = 0;
        for (FixtureResponse fixture : fixtures) {
            if (fixture.fixture() == null || fixture.fixture().id() == null
                    || fixture.teams() == null || fixture.teams().home() == null || fixture.teams().away() == null) {
                log.warn("Fixture ignorado por falta de dados essenciais: {}", fixture);
                continue;
            }
            // Transação por fixture, commitada ainda dentro do lock
            eventosNovos += transacao.execute(status -> ingerirFixture(fixture));
        }
        return eventosNovos;
    }

    /** Plano Free não tem busca por ids em lote: consultas individuais, limitadas por poll. */
    private static final int LIMITE_RECONCILIACOES_POR_POLL = 10;

    /** Nenhuma partida de futebol dura isso: em aberto há mais tempo é encerrada localmente. */
    private static final java.time.Duration IDADE_MAXIMA_EM_JOGO = java.time.Duration.ofHours(5);

    private int encerrarPartidasForaDoFeed(List<FixtureResponse> feed) {
        Set<Long> idsNoFeed = new HashSet<>();
        for (FixtureResponse fixture : feed) {
            if (fixture.fixture() != null && fixture.fixture().id() != null) {
                idsNoFeed.add(fixture.fixture().id());
            }
        }

        int novos = 0;
        java.time.Instant limiteIdade = java.time.Instant.now().minus(IDADE_MAXIMA_EM_JOGO);
        List<Long> paraConsultar = new ArrayList<>();
        for (Object[] linha : eventoRepository.findPartidasEmAbertoComInicio(
                TipoEvento.MATCH_STARTED, EVENTOS_TERMINAIS)) {
            Long apiFixtureId = (Long) linha[0];
            java.time.Instant vistaDesde = (java.time.Instant) linha[1];
            if (idsNoFeed.contains(apiFixtureId)) {
                continue;
            }
            if (vistaDesde.isBefore(limiteIdade)) {
                novos += transacao.execute(status -> encerrarLocalmente(apiFixtureId));
            } else if (paraConsultar.size() < LIMITE_RECONCILIACOES_POR_POLL) {
                paraConsultar.add(apiFixtureId);
            }
        }

        for (Long apiFixtureId : paraConsultar) {
            try {
                novos += ingerirLista(client.buscarJogoPorId(apiFixtureId));
            } catch (com.placarcopa.dataprovider.ApiFootballException e) {
                // fica para o próximo poll — não derruba a ingestão do feed
                log.warn("Falha ao reconciliar fixture {}: {}", apiFixtureId, e.getMessage());
            }
        }
        if (novos > 0) {
            log.info("Reconciliacao: {} eventos de encerramento gerados para partidas fora do feed", novos);
        }
        return novos;
    }

    /**
     * Sem desfecho do provider dentro da janela plausível de jogo: encerra com
     * um MATCH_ENDED sintético para o placar não ficar "ao vivo" para sempre.
     */
    private int encerrarLocalmente(Long apiFixtureId) {
        List<EventoPartida> eventos = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(apiFixtureId);
        if (eventos.isEmpty()) {
            return 0;
        }
        EventoPartida ultimo = eventos.get(eventos.size() - 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auto_closed", true);
        payload.put("reason", "sem desfecho do provider apos " + IDADE_MAXIMA_EM_JOGO.toHours() + "h");
        EventoPartida salvo = eventoRepository.save(new EventoPartida(
                ultimo.getMatchCode(), ultimo.getTeamA(), ultimo.getTeamB(),
                ultimo.getCompetitionTitle(), ultimo.getCompetitionStage(),
                TipoEvento.MATCH_ENDED, ultimo.getMinute(), ultimo.getSequence() + 1,
                payload, apiFixtureId));
        eventPublisher.publishEvent(new EventoPartidaCriado(salvo));
        log.info("Partida {} encerrada automaticamente (fora do feed ha mais de {}h)",
                ultimo.getMatchCode(), IDADE_MAXIMA_EM_JOGO.toHours());
        return 1;
    }

    private int ingerirFixture(FixtureResponse fixture) {
        Long apiFixtureId = fixture.fixture().id();
        List<EventoPartida> existentes = eventoRepository.findByApiFixtureIdOrderBySequenceAsc(apiFixtureId);

        // Chaves com índice de ocorrência: dois eventos idênticos (mesmo jogador,
        // minuto e detalhe) recebem sufixos #1/#2 e não colapsam na deduplicação.
        Map<EventoPartida, String> chaveDoExistente = new LinkedHashMap<>();
        Map<String, Integer> ocorrenciasExistentes = new HashMap<>();
        Set<String> chavesExistentes = new HashSet<>();
        int ultimaSequence = 0;
        for (EventoPartida existente : existentes) {
            String chave = comOcorrencia(EventoProposto.chave(
                    existente.getEvent(), existente.getMinute(), existente.getPayload()), ocorrenciasExistentes);
            chaveDoExistente.put(existente, chave);
            chavesExistentes.add(chave);
            ultimaSequence = Math.max(ultimaSequence, existente.getSequence());
        }

        // O match code é definido na primeira ingestão do fixture e reutilizado depois;
        // colisão entre fixtures diferentes na mesma data ganha o sufixo do id da API.
        String matchCode = existentes.isEmpty()
                ? resolverMatchCode(fixture, apiFixtureId)
                : existentes.get(0).getMatchCode();
        String competitionTitle = mapper.competitionTitle(fixture);
        String competitionStage = mapper.competitionStage(fixture);
        String teamA = fixture.teams().home().name();
        String teamB = fixture.teams().away().name();

        List<EventoProposto> propostos = new ArrayList<>(mapper.extrairEventos(fixture));
        Map<String, Integer> ocorrenciasPropostas = new HashMap<>();
        Set<String> chavesPropostas = new HashSet<>();
        for (EventoProposto proposto : propostos) {
            chavesPropostas.add(comOcorrencia(proposto.chaveDeduplicacao(), ocorrenciasPropostas));
        }

        propostos.addAll(compensacoesDeGolsAnulados(
                existentes, chaveDoExistente, chavesPropostas, propostos, fixture));

        int novos = 0;
        Map<String, Integer> ocorrenciasInsercao = new HashMap<>();
        for (EventoProposto proposto : propostos) {
            String chave = comOcorrencia(proposto.chaveDeduplicacao(), ocorrenciasInsercao);
            if (!chavesExistentes.add(chave)) {
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

    /**
     * Gol que registramos sumiu da timeline da API E há uma decisão de VAR de
     * anulação para o mesmo time → emite um GOAL_CANCELLED de compensação.
     * A exigência dos dois sinais evita falso positivo quando a timeline oscila.
     */
    private List<EventoProposto> compensacoesDeGolsAnulados(List<EventoPartida> existentes,
                                                            Map<EventoPartida, String> chaveDoExistente,
                                                            Set<String> chavesPropostas,
                                                            List<EventoProposto> propostos,
                                                            FixtureResponse fixture) {
        if (fixture.events() == null) {
            return List.of();
        }

        Set<Object> timesComGolAnuladoPeloVar = new HashSet<>();
        for (EventoProposto proposto : propostos) {
            if (proposto.event() == TipoEvento.VAR_DECISION && proposto.payload() != null) {
                String detalhe = String.valueOf(proposto.payload().get("detail")).toLowerCase(Locale.ROOT);
                if (detalhe.contains("cancel") || detalhe.contains("disallow")) {
                    timesComGolAnuladoPeloVar.add(proposto.payload().get("team"));
                }
            }
        }
        if (timesComGolAnuladoPeloVar.isEmpty()) {
            return List.of();
        }

        Set<Object> jaCompensados = new HashSet<>();
        for (EventoPartida existente : existentes) {
            if (existente.getEvent() == TipoEvento.GOAL_CANCELLED) {
                jaCompensados.add(existente.getPayload().get("cancels"));
            }
        }

        List<EventoProposto> compensacoes = new ArrayList<>();
        for (EventoPartida existente : existentes) {
            if (!TIPOS_DE_GOL.contains(existente.getEvent())) {
                continue;
            }
            String chave = chaveDoExistente.get(existente);
            Object time = existente.getPayload().get("team");
            boolean sumiuDaTimeline = !chavesPropostas.contains(chave);
            boolean varAnulouDoMesmoTime = timesComGolAnuladoPeloVar.contains(time);
            if (sumiuDaTimeline && varAnulouDoMesmoTime && !jaCompensados.contains(chave)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (time != null) {
                    payload.put("team", time);
                }
                Object jogador = existente.getPayload().get("player");
                if (jogador != null) {
                    payload.put("player", jogador);
                }
                payload.put("cancelled_type", existente.getEvent().name());
                payload.put("cancels", chave);
                compensacoes.add(new EventoProposto(TipoEvento.GOAL_CANCELLED, existente.getMinute(), payload));
                log.info("Gol anulado pelo VAR detectado ({} seq {}): emitindo compensacao",
                        existente.getMatchCode(), existente.getSequence());
            }
        }
        return compensacoes;
    }

    private String resolverMatchCode(FixtureResponse fixture, Long apiFixtureId) {
        String base = mapper.matchCode(fixture);
        if (eventoRepository.existsByMatchCodeAndApiFixtureIdNot(base, apiFixtureId)) {
            return base + "-" + apiFixtureId;
        }
        return base;
    }

    private static String comOcorrencia(String chaveBase, Map<String, Integer> contador) {
        int ocorrencia = contador.merge(Objects.requireNonNullElse(chaveBase, "null"), 1, Integer::sum);
        return chaveBase + "#" + ocorrencia;
    }
}
