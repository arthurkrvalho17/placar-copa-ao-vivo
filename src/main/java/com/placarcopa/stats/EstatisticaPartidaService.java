package com.placarcopa.stats;

import com.placarcopa.domain.EstatisticaPartida;
import com.placarcopa.messaging.MatchEventMessage;
import com.placarcopa.repository.EstatisticaPartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read model persistente: acumula as estatísticas gerais das partidas no banco
 * a partir dos eventos consumidos pelo consumer group de estatísticas.
 */
@Service
public class EstatisticaPartidaService {

    private final EstatisticaPartidaRepository repository;

    public EstatisticaPartidaService(EstatisticaPartidaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void aplicar(MatchEventMessage mensagem) {
        EstatisticaPartida estatistica = repository.findByMatchCode(mensagem.match())
                .orElseGet(() -> new EstatisticaPartida(
                        mensagem.match(), mensagem.teamA(), mensagem.teamB(),
                        mensagem.competition() == null ? null : mensagem.competition().title(),
                        mensagem.competition() == null ? null : mensagem.competition().stage()));
        if (estatistica.aplicar(mensagem)) {
            repository.save(estatistica);
        }
    }

    @Transactional(readOnly = true)
    public List<EstatisticaPartida> listar() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<EstatisticaPartida> buscar(String matchCode) {
        return repository.findByMatchCode(matchCode);
    }
}
