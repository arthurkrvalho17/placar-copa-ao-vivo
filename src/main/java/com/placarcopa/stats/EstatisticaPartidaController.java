package com.placarcopa.stats;

import com.placarcopa.domain.EstatisticaPartida;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/estatisticas")
public class EstatisticaPartidaController {

    private final EstatisticaPartidaService service;

    public EstatisticaPartidaController(EstatisticaPartidaService service) {
        this.service = service;
    }

    @GetMapping
    public List<EstatisticaPartida> listar() {
        return service.listar();
    }

    @GetMapping("/{match}")
    public EstatisticaPartida buscar(@PathVariable String match) {
        return service.buscar(match)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sem estatísticas para a partida " + match));
    }
}
