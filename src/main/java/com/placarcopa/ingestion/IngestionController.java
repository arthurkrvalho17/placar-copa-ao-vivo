package com.placarcopa.ingestion;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dispara a ingestão manualmente. Mais adiante um scheduler fará isso sozinho.
 */
@RestController
@RequestMapping("/api/ingestao")
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping("/ao-vivo")
    public IngestionSummary aoVivo(@RequestParam(required = false) List<Long> ligas) {
        return service.ingerirAoVivo(ligas);
    }

    @PostMapping("/ligas/{ligaId}/temporadas/{temporada}")
    public IngestionSummary competicao(@PathVariable long ligaId, @PathVariable int temporada) {
        return service.ingerirCompeticao(ligaId, temporada);
    }
}
