package com.placarcopa.domain;

/**
 * Resolve a qual lado da partida (time A ou B) um evento pertence.
 * Único lugar do sistema que compara o time do payload com os times da partida —
 * os read models compartilham esta regra em vez de reimplementá-la.
 */
public enum LadoPartida {
    A, B, NENHUM;

    public static LadoPartida de(Object nomeTime, String teamA, String teamB) {
        if (teamA != null && teamA.equals(nomeTime)) {
            return A;
        }
        if (teamB != null && teamB.equals(nomeTime)) {
            return B;
        }
        return NENHUM;
    }
}
