package com.placarcopa.stats;

import com.placarcopa.messaging.MatchEventMessage;
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
 */
@Service
public class PlacarAoVivoService {

    static final String PREFIXO_CHAVE = "placares:partida:";
    static final String CHAVE_INDICE = "placares:indice";
    private static final Duration VALIDADE = Duration.ofHours(24);

    private final RedisTemplate<String, PlacarAoVivo> redis;
    private final StringRedisTemplate stringRedis;

    public PlacarAoVivoService(RedisTemplate<String, PlacarAoVivo> placarRedisTemplate,
                               StringRedisTemplate stringRedisTemplate) {
        this.redis = placarRedisTemplate;
        this.stringRedis = stringRedisTemplate;
    }

    public PlacarAoVivo aplicar(MatchEventMessage mensagem) {
        String chave = PREFIXO_CHAVE + mensagem.match();
        PlacarAoVivo atual = redis.opsForValue().get(chave);
        PlacarAoVivo base = atual == null ? PlacarAoVivo.inicial(mensagem) : atual;
        PlacarAoVivo novo = base.aplicar(mensagem);

        // Mensagem duplicada/atrasada não regrava nem republica
        if (novo == atual) {
            return atual;
        }

        redis.opsForValue().set(chave, novo, VALIDADE);
        stringRedis.opsForSet().add(CHAVE_INDICE, mensagem.match());
        stringRedis.expire(CHAVE_INDICE, VALIDADE);
        redis.convertAndSend(RedisConfig.CANAL_ATUALIZACOES, novo);
        return novo;
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
