package com.placarcopa.stats;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/placares")
public class PlacarAoVivoController {

    private final PlacarAoVivoService service;
    private final PlacarStreamService streamService;

    public PlacarAoVivoController(PlacarAoVivoService service, PlacarStreamService streamService) {
        this.service = service;
        this.streamService = streamService;
    }

    @GetMapping
    public List<PlacarAoVivo> listar() {
        return service.listar();
    }

    @GetMapping("/{match}")
    public PlacarAoVivo buscar(@PathVariable String match) {
        return service.buscar(match)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sem placar para a partida " + match));
    }

    /**
     * Stream em tempo real para o frontend: snapshot do estado atual (do Redis)
     * seguido de cada atualização empurrada pelo Redis pub/sub.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return streamService.inscrever(service::listar);
    }
}
