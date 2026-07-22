package com.placarcopa.stats;

import com.placarcopa.domain.LadoPartida;
import com.placarcopa.messaging.MatchEventMessage;

import java.time.Instant;

/**
 * Estado de consulta rápida de uma partida (placar, cartões, status),
 * derivado exclusivamente da sequência de eventos. Armazenado no Redis.
 *
 * {@code atualizadoEm} existe para o frontend detectar dados parados (ex.:
 * cota do provider esgotada): sem ele, uma partida cujo último evento veio há
 * horas parece tão "ao vivo" quanto uma atualizada agora mesmo.
 */
public record PlacarAoVivo(
        String match,
        String teamA,
        String teamB,
        int golsTeamA,
        int golsTeamB,
        int cartoesAmarelosTeamA,
        int cartoesAmarelosTeamB,
        int cartoesVermelhosTeamA,
        int cartoesVermelhosTeamB,
        String status,
        String minute,
        int ultimaSequence,
        Instant atualizadoEm
) {

    public static PlacarAoVivo inicial(MatchEventMessage mensagem) {
        return new PlacarAoVivo(mensagem.match(), mensagem.teamA(), mensagem.teamB(),
                0, 0, 0, 0, 0, 0, null, null, 0, Instant.now());
    }

    /**
     * Aplica um evento e devolve o novo estado. Mensagem com sequence já
     * aplicado devolve {@code this} inalterado (entrega at-least-once).
     */
    public PlacarAoVivo aplicar(MatchEventMessage mensagem) {
        if (mensagem.sequence() <= ultimaSequence) {
            return this;
        }

        int golsA = golsTeamA;
        int golsB = golsTeamB;
        int amarelosA = cartoesAmarelosTeamA;
        int amarelosB = cartoesAmarelosTeamB;
        int vermelhosA = cartoesVermelhosTeamA;
        int vermelhosB = cartoesVermelhosTeamB;

        Object time = mensagem.payload() == null ? null : mensagem.payload().get("team");
        LadoPartida lado = LadoPartida.de(time, teamA, teamB);

        switch (mensagem.event()) {
            // Na API-Football o team do evento de gol é sempre o time beneficiado,
            // inclusive no gol contra (o jogador é do adversário)
            case GOAL, PENALTY_GOAL, OWN_GOAL -> {
                if (lado == LadoPartida.A) {
                    golsA++;
                } else if (lado == LadoPartida.B) {
                    golsB++;
                }
            }
            case GOAL_CANCELLED -> {
                if (lado == LadoPartida.A) {
                    golsA = Math.max(0, golsA - 1);
                } else if (lado == LadoPartida.B) {
                    golsB = Math.max(0, golsB - 1);
                }
            }
            case YELLOW_CARD -> {
                if (lado == LadoPartida.A) {
                    amarelosA++;
                } else if (lado == LadoPartida.B) {
                    amarelosB++;
                }
            }
            case RED_CARD -> {
                if (lado == LadoPartida.A) {
                    vermelhosA++;
                } else if (lado == LadoPartida.B) {
                    vermelhosB++;
                }
            }
            default -> {
                // demais eventos não alteram placar nem cartões
            }
        }

        String novoStatus = mensagem.event().isCicloDeVida() ? mensagem.event().name() : status;
        String novoMinuto = mensagem.minute() == null ? minute : mensagem.minute();
        return new PlacarAoVivo(match, teamA, teamB,
                golsA, golsB, amarelosA, amarelosB, vermelhosA, vermelhosB,
                novoStatus, novoMinuto, mensagem.sequence(), Instant.now());
    }
}
