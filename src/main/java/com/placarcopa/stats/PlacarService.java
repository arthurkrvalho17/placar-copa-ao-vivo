package com.placarcopa.stats;

import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Estatísticas derivadas dos eventos — o placar não é armazenado em lugar nenhum,
 * é sempre calculado a partir da sequência de eventos da partida.
 */
@Service
public class PlacarService {

    private static final Set<TipoEvento> GOLS_A_FAVOR = Set.of(TipoEvento.GOAL, TipoEvento.PENALTY_GOAL);

    public record Placar(String teamA, String teamB, int golsTeamA, int golsTeamB) {
    }

    public Placar calcular(List<EventoPartida> eventos) {
        if (eventos.isEmpty()) {
            throw new IllegalArgumentException("Não há eventos para calcular o placar");
        }
        String teamA = eventos.get(0).getTeamA();
        String teamB = eventos.get(0).getTeamB();

        int golsA = 0;
        int golsB = 0;
        for (EventoPartida evento : eventos) {
            Object time = evento.getPayload().get("team");
            if (GOLS_A_FAVOR.contains(evento.getEvent())) {
                if (teamA.equals(time)) {
                    golsA++;
                } else if (teamB.equals(time)) {
                    golsB++;
                }
            } else if (evento.getEvent() == TipoEvento.OWN_GOAL) {
                // gol contra conta para o adversário
                if (teamA.equals(time)) {
                    golsB++;
                } else if (teamB.equals(time)) {
                    golsA++;
                }
            }
        }
        return new Placar(teamA, teamB, golsA, golsB);
    }
}
