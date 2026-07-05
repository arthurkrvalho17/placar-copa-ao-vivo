package com.placarcopa.stats;

import com.placarcopa.messaging.MatchEventMessage;

/**
 * Estado de consulta rápida de uma partida (placar, cartões, status),
 * derivado exclusivamente da sequência de eventos. Armazenado no Redis.
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
        int ultimaSequence
) {

    public static PlacarAoVivo inicial(MatchEventMessage mensagem) {
        return new PlacarAoVivo(mensagem.match(), mensagem.teamA(), mensagem.teamB(),
                0, 0, 0, 0, 0, 0, null, null, 0);
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
        boolean doTimeA = teamA.equals(time);
        boolean doTimeB = teamB.equals(time);

        switch (mensagem.event()) {
            case GOAL, PENALTY_GOAL -> {
                if (doTimeA) {
                    golsA++;
                } else if (doTimeB) {
                    golsB++;
                }
            }
            case OWN_GOAL -> {
                if (doTimeA) {
                    golsB++;
                } else if (doTimeB) {
                    golsA++;
                }
            }
            case YELLOW_CARD -> {
                if (doTimeA) {
                    amarelosA++;
                } else if (doTimeB) {
                    amarelosB++;
                }
            }
            case RED_CARD -> {
                if (doTimeA) {
                    vermelhosA++;
                } else if (doTimeB) {
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
                novoStatus, novoMinuto, mensagem.sequence());
    }
}
