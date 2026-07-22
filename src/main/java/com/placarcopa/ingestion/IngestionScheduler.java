package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.ApiFootballQuotaExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Poll contínuo do feed ao vivo. Além de ingerir os jogos em andamento, é o que
 * garante o MATCH_ENDED: a API não emite evento de fim de jogo e o fixture some
 * do feed ao terminar, então o desfecho só é capturado pela reconciliação que
 * roda dentro de cada poll ({@link IngestionService#ingerirAoVivo}) — e ela
 * precisa continuar executando mesmo quando não há mais jogos no feed.
 *
 * Quando a cota diária do provider esgota, insistir a cada tick é inútil e
 * desperdiça o que resta da cota: o scheduler entra em backoff exponencial
 * (começa em {@link #BACKOFF_INICIAL}, dobra até {@link #BACKOFF_MAXIMO}) e só
 * tenta de novo depois da janela — sem isso, o último estado ingerido antes do
 * esgotamento fica congelado indefinidamente, sem sinalização de que os dados
 * pararam de atualizar.
 */
@Component
@ConditionalOnProperty(name = "ingestao.agendada.habilitada", havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
    private static final Duration BACKOFF_INICIAL = Duration.ofMinutes(15);
    private static final Duration BACKOFF_MAXIMO = Duration.ofHours(2);

    private final IngestionService service;
    private final IngestionStatus status;
    private final List<Long> ligas;

    private final AtomicReference<Instant> proximaTentativaPermitida = new AtomicReference<>();
    private Duration backoffAtual = BACKOFF_INICIAL;

    public IngestionScheduler(IngestionService service,
                              IngestionStatus status,
                              @Value("${ingestao.agendada.ligas:}") List<Long> ligas) {
        this.service = service;
        this.status = status;
        this.ligas = ligas;
    }

    /** A API recomenda no máximo 1 chamada/minuto por recurso ao vivo. */
    @Scheduled(fixedDelayString = "${ingestao.agendada.intervalo:60s}")
    public void ingerirAoVivo() {
        Instant espera = proximaTentativaPermitida.get();
        if (espera != null && Instant.now().isBefore(espera)) {
            return; // em backoff: não vale a pena gastar mais chamadas da cota
        }

        try {
            service.ingerirAoVivo(ligas);
            status.registrarSucesso();
            backoffAtual = BACKOFF_INICIAL; // sucesso reseta o backoff
            proximaTentativaPermitida.set(null);
        } catch (ApiFootballQuotaExhaustedException e) {
            Instant proxima = Instant.now().plus(backoffAtual);
            proximaTentativaPermitida.set(proxima);
            status.registrarCotaEsgotada(e.getMessage(), proxima);
            log.warn("Cota da API-Football esgotada; proxima tentativa em {} ({})", backoffAtual, proxima);
            backoffAtual = backoffAtual.multipliedBy(2).compareTo(BACKOFF_MAXIMO) > 0
                    ? BACKOFF_MAXIMO : backoffAtual.multipliedBy(2);
        } catch (RuntimeException e) {
            // falha pontual (rede, HTTP 5xx...): próximo poll tenta de novo sem backoff
            status.registrarFalhaTransitoria(e.getMessage());
            log.warn("Ingestao agendada falhou: {}", e.getMessage());
        }
    }
}
