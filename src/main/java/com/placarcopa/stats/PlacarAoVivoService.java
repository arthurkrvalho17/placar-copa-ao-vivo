package com.placarcopa.stats;

import com.placarcopa.domain.EventoPartida;
import com.placarcopa.messaging.MatchEventMessage;
import com.placarcopa.repository.EventoPartidaRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read model de consultas rápidas do frontend, armazenado no Redis.
 * A cada evento consumido do Kafka, o placar da partida é atualizado e a
 * versão nova é publicada no canal pub/sub para os assinantes em tempo real.
 *
 * Invariante: ultimaSequence só avança de forma contígua — um evento fora de
 * ordem (gap à frente, ou estado expirado no Redis) dispara o complemento a
 * partir do event store, que sempre contém o prefixo completo (os eventos são
 * persistidos antes de qualquer publicação). Com isso, sequence menor ou igual
 * à última aplicada é garantidamente duplicata e pode ser descartada.
 */
@Service
public class PlacarAoVivoService {

    static final String PREFIXO_CHAVE = "placares:partida:";
    static final String CHAVE_INDICE = "placares:indice";
    private static final Duration VALIDADE = Duration.ofHours(24);
    /** Partida com desfecho sai da vitrine mais cedo. */
    private static final Duration VALIDADE_ENCERRADA = Duration.ofHours(4);
    private static final Set<String> STATUS_TERMINAIS = Set.of(
            "MATCH_ENDED", "MATCH_CANCELLED", "MATCH_POSTPONED");

    private final RedisTemplate<String, PlacarAoVivo> redis;
    private final StringRedisTemplate stringRedis;
    private final EventoPartidaRepository eventoRepository;

    public PlacarAoVivoService(RedisTemplate<String, PlacarAoVivo> placarRedisTemplate,
                               StringRedisTemplate stringRedisTemplate,
                               EventoPartidaRepository eventoRepository) {
        this.redis = placarRedisTemplate;
        this.stringRedis = stringRedisTemplate;
        this.eventoRepository = eventoRepository;
    }

    public PlacarAoVivo aplicar(MatchEventMessage mensagem) {
        String chave = PREFIXO_CHAVE + mensagem.match();
        PlacarAoVivo atual = redis.opsForValue().get(chave);
        int ultimaAplicada = atual == null ? 0 : atual.ultimaSequence();

        // Duplicata: pela contiguidade, tudo até ultimaSequence já foi aplicado
        if (mensagem.sequence() <= ultimaAplicada) {
            return atual;
        }

        PlacarAoVivo novo = mensagem.sequence() == ultimaAplicada + 1
                ? (atual == null ? PlacarAoVivo.inicial(mensagem) : atual).aplicar(mensagem)
                : completarDoEventStore(mensagem.match(), atual);

        if (novo == null || novo.equals(atual)) {
            return atual;
        }

        Duration validade = STATUS_TERMINAIS.contains(novo.status()) ? VALIDADE_ENCERRADA : VALIDADE;
        redis.opsForValue().set(chave, novo, validade);
        stringRedis.opsForSet().add(CHAVE_INDICE, mensagem.match());
        stringRedis.expire(CHAVE_INDICE, VALIDADE);
        redis.convertAndSend(RedisConfig.CANAL_ATUALIZACOES, novo);
        return novo;
    }

    /**
     * Gap na entrega (evento adiantado, reentrega tardia ou chave expirada):
     * em vez de aplicar fora de ordem — ou reconstruir do zero a partir de uma
     * mensagem de meio de partida — completa a projeção com os eventos que
     * faltam, lidos do event store em ordem de sequence.
     */
    private PlacarAoVivo completarDoEventStore(String matchCode, PlacarAoVivo atual) {
        int ultimaAplicada = atual == null ? 0 : atual.ultimaSequence();
        List<EventoPartida> faltantes = eventoRepository
                .findByMatchCodeAndSequenceGreaterThanOrderBySequenceAsc(matchCode, ultimaAplicada);
        if (faltantes.isEmpty()) {
            return atual;
        }
        PlacarAoVivo placar = atual != null
                ? atual
                : PlacarAoVivo.inicial(MatchEventMessage.de(faltantes.get(0)));
        for (EventoPartida evento : faltantes) {
            placar = placar.aplicar(MatchEventMessage.de(evento));
        }
        return placar;
    }

    public List<PlacarAoVivo> listar() {
        Set<String> partidas = stringRedis.opsForSet().members(CHAVE_INDICE);
        if (partidas == null || partidas.isEmpty()) {
            return List.of();
        }
        List<String> chaves = partidas.stream().map(match -> PREFIXO_CHAVE + match).toList();
        List<PlacarAoVivo> valores = redis.opsForValue().multiGet(chaves);
        return valores == null ? List.of()
                : valores.stream().filter(Objects::nonNull).toList();
    }

    public Optional<PlacarAoVivo> buscar(String match) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIXO_CHAVE + match));
    }
}
