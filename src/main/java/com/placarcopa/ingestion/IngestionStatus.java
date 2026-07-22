package com.placarcopa.ingestion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado observável do poll de ingestão — permite ao frontend (e a operadores)
 * saber se os dados estão frescos ou se o backend está com a cota da
 * API-Football esgotada, em vez de inferir isso do silêncio.
 */
@Component
public class IngestionStatus {

    public record Situacao(
            Instant ultimoSucessoEm,
            Instant ultimaFalhaEm,
            String ultimoErro,
            boolean cotaEsgotada,
            Instant proximaTentativaEm
    ) {
        static final Situacao INICIAL = new Situacao(null, null, null, false, null);
    }

    private final AtomicReference<Situacao> situacao = new AtomicReference<>(Situacao.INICIAL);

    public void registrarSucesso() {
        situacao.set(new Situacao(Instant.now(), null, null, false, null));
    }

    public void registrarCotaEsgotada(String motivo, Instant proximaTentativaEm) {
        Situacao atual = situacao.get();
        situacao.set(new Situacao(atual.ultimoSucessoEm(), Instant.now(), motivo, true, proximaTentativaEm));
    }

    public void registrarFalhaTransitoria(String motivo) {
        Situacao atual = situacao.get();
        situacao.set(new Situacao(atual.ultimoSucessoEm(), Instant.now(), motivo, false, null));
    }

    public Situacao atual() {
        return situacao.get();
    }
}
