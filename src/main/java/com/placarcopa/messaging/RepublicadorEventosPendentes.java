package com.placarcopa.messaging;

import com.placarcopa.domain.EventoPartida;
import com.placarcopa.repository.EventoPartidaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Rede de segurança da ponte banco→Kafka (outbox): eventos persistidos cuja
 * publicação não foi confirmada são reenviados periodicamente, em ordem de
 * sequence por partida. Consumers são idempotentes, então reenvio é seguro.
 *
 * O reenvio é limitado a uma janela de idade: pendentes mais velhos que a
 * janela não voltam ao Kafka automaticamente — sem isso, uma coluna nova
 * backfillada com publicado=false (ou um backup restaurado) reinjetaria o
 * histórico inteiro e ressuscitaria partidas encerradas no placar ao vivo.
 * As projeções se recuperam pelo complemento via event store nos consumers.
 */
@Component
public class RepublicadorEventosPendentes {

    private static final Logger log = LoggerFactory.getLogger(RepublicadorEventosPendentes.class);

    /** Espera antes de considerar um evento pendente, para não competir com a publicação normal. */
    private static final Duration IDADE_MINIMA = Duration.ofSeconds(20);

    /** Pendente mais velho que isso não é reenviado automaticamente. */
    private static final Duration IDADE_MAXIMA = Duration.ofHours(24);

    private final EventoPartidaRepository eventoRepository;
    private final MatchEventPublisher publisher;
    private long pendentesExpiradosNoUltimoCiclo = -1;

    public RepublicadorEventosPendentes(EventoPartidaRepository eventoRepository,
                                        MatchEventPublisher publisher) {
        this.eventoRepository = eventoRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 30_000)
    public void republicarPendentes() {
        Instant agora = Instant.now();
        alertarPendentesForaDaJanela(agora);

        List<EventoPartida> pendentes = eventoRepository
                .findTop200ByPublicadoFalseAndCriadoEmBetweenOrderByMatchCodeAscSequenceAsc(
                        agora.minus(IDADE_MAXIMA), agora.minus(IDADE_MINIMA));
        if (pendentes.isEmpty()) {
            return;
        }
        log.warn("Republicando {} eventos com publicacao nao confirmada", pendentes.size());
        for (EventoPartida evento : pendentes) {
            publisher.publicar(MatchEventMessage.de(evento), evento.getId());
        }
    }

    /** Loga só quando a contagem muda, para não repetir o aviso a cada 30s. */
    private void alertarPendentesForaDaJanela(Instant agora) {
        long expirados = eventoRepository.countByPublicadoFalseAndCriadoEmBefore(agora.minus(IDADE_MAXIMA));
        if (expirados > 0 && expirados != pendentesExpiradosNoUltimoCiclo) {
            log.warn("{} eventos pendentes ha mais de {}h nao serao reenviados automaticamente",
                    expirados, IDADE_MAXIMA.toHours());
        }
        pendentesExpiradosNoUltimoCiclo = expirados;
    }
}
