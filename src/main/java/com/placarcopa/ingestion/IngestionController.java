package com.placarcopa.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dispara a ingestão manualmente (o poll contínuo fica no IngestionScheduler;
 * estes endpoints servem para backfill e testes).
 */
@RestController
@RequestMapping("/api/ingestao")
public class IngestionController {

    private final IngestionService service;
    private final IngestionStatus status;

    public IngestionController(IngestionService service, IngestionStatus status) {
        this.service = service;
        this.status = status;
    }

    @PostMapping("/ao-vivo")
    public IngestionSummary aoVivo(@RequestParam(required = false) List<Long> ligas) {
        return service.ingerirAoVivo(ligas);
    }

    @PostMapping("/ligas/{ligaId}/temporadas/{temporada}")
    public IngestionSummary competicao(@PathVariable long ligaId, @PathVariable int temporada) {
        return service.ingerirCompeticao(ligaId, temporada);
    }

    /** Permite ao frontend saber se os placares exibidos estão frescos ou parados por cota. */
    @GetMapping("/status")
    public IngestionStatus.Situacao status() {
        return status.atual();
    }
}
