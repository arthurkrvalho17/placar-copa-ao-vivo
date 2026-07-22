package com.placarcopa.stats;

import com.placarcopa.domain.EstatisticaPartida;
import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import com.placarcopa.messaging.MatchEventMessage;
import com.placarcopa.repository.EstatisticaPartidaRepository;
import com.placarcopa.repository.EventoPartidaRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixa o invariante de contiguidade do consumer: gap na entrega é completado
 * a partir do event store, e sequence já aplicada é descartada como duplicata.
 */
class EstatisticaPartidaServiceTest {

    private static final String MATCH = "ENG-GAN-13-07-2026";

    private final EstatisticaPartidaRepository repository = mock(EstatisticaPartidaRepository.class);
    private final EventoPartidaRepository eventoRepository = mock(EventoPartidaRepository.class);
    private final EstatisticaPartidaService service = new EstatisticaPartidaService(repository, eventoRepository);

    @Test
    void deveCompletarGapPeloEventStoreEmVezDeAplicarForaDeOrdem() {
        // Nenhum estado ainda, e a primeira mensagem a chegar é a seq 3 (gap):
        // o gol da seq 2, cujo send original falhou, não pode se perder
        when(repository.findByMatchCode(MATCH)).thenReturn(Optional.empty());
        when(eventoRepository.findByMatchCodeAndSequenceGreaterThanOrderBySequenceAsc(MATCH, 0))
                .thenReturn(List.of(
                        evento(TipoEvento.MATCH_STARTED, 0, 1, Map.of()),
                        evento(TipoEvento.GOAL, 22, 2, Map.of("team", "England")),
                        evento(TipoEvento.YELLOW_CARD, 30, 3, Map.of("team", "GANA"))));

        service.aplicar(mensagem(TipoEvento.YELLOW_CARD, 3, Map.of("team", "GANA")));

        ArgumentCaptor<EstatisticaPartida> salvo = ArgumentCaptor.forClass(EstatisticaPartida.class);
        verify(repository).save(salvo.capture());
        assertThat(salvo.getValue().getGolsTeamA()).isEqualTo(1);
        assertThat(salvo.getValue().getCartoesAmarelosTeamB()).isEqualTo(1);
        assertThat(salvo.getValue().getUltimaSequence()).isEqualTo(3);
    }

    @Test
    void deveDescartarSequenceJaAplicadaComoDuplicata() {
        EstatisticaPartida existente = new EstatisticaPartida(MATCH, "England", "GANA", null, null);
        existente.aplicar(mensagem(TipoEvento.MATCH_STARTED, 1, Map.of()));
        when(repository.findByMatchCode(MATCH)).thenReturn(Optional.of(existente));

        service.aplicar(mensagem(TipoEvento.MATCH_STARTED, 1, Map.of()));

        verify(repository, never()).save(existente);
        verify(eventoRepository, never())
                .findByMatchCodeAndSequenceGreaterThanOrderBySequenceAsc(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static MatchEventMessage mensagem(TipoEvento evento, int sequence, Map<String, Object> payload) {
        return new MatchEventMessage("1", MATCH, "England", "GANA", null, evento,
                "10", sequence, payload);
    }

    private static EventoPartida evento(TipoEvento tipo, Integer minuto, int sequence, Map<String, Object> payload) {
        return new EventoPartida(MATCH, "England", "GANA", "world-cup-2026", "groups_stage",
                tipo, minuto, sequence, payload, 12010L);
    }
}
