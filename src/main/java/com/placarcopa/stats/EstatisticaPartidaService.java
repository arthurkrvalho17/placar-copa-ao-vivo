package com.placarcopa.stats;

import com.placarcopa.domain.EstatisticaPartida;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.messaging.MatchEventMessage;
import com.placarcopa.repository.EstatisticaPartidaRepository;
import com.placarcopa.repository.EventoPartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read model persistente: acumula as estatísticas gerais das partidas no banco
 * a partir dos eventos consumidos pelo consumer group de estatísticas.
 *
 * Invariante: ultimaSequence só avança de forma contígua — evento fora de ordem
 * dispara o complemento a partir do event store (que sempre tem o prefixo
 * completo, pois os eventos são persistidos antes de qualquer publicação).
 * Assim, sequence menor ou igual à última aplicada é garantidamente duplicata.
 */
@Service
public class EstatisticaPartidaService {

    private final EstatisticaPartidaRepository repository;
    private final EventoPartidaRepository eventoRepository;

    public EstatisticaPartidaService(EstatisticaPartidaRepository repository,
                                     EventoPartidaRepository eventoRepository) {
        this.repository = repository;
        this.eventoRepository = eventoRepository;
    }

    @Transactional
    public void aplicar(MatchEventMessage mensagem) {
        EstatisticaPartida estatistica = repository.findByMatchCode(mensagem.match())
                .orElseGet(() -> new EstatisticaPartida(
                        mensagem.match(), mensagem.teamA(), mensagem.teamB(),
                        mensagem.competition() == null ? null : mensagem.competition().title(),
                        mensagem.competition() == null ? null : mensagem.competition().stage()));

        if (mensagem.sequence() <= estatistica.getUltimaSequence()) {
            return;
        }

        boolean mudou;
        if (mensagem.sequence() == estatistica.getUltimaSequence() + 1) {
            mudou = estatistica.aplicar(mensagem);
        } else {
            // Gap na entrega: completa com os eventos que faltam, em ordem, do event store
            mudou = false;
            List<EventoPartida> faltantes = eventoRepository
                    .findByMatchCodeAndSequenceGreaterThanOrderBySequenceAsc(
                            mensagem.match(), estatistica.getUltimaSequence());
            for (EventoPartida evento : faltantes) {
                mudou |= estatistica.aplicar(MatchEventMessage.de(evento));
            }
        }
        if (mudou) {
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
