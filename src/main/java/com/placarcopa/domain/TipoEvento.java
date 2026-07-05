package com.placarcopa.domain;

/**
 * Tipos de evento de partida. Os nomes são os valores do campo {@code event}
 * das mensagens (ex.: MATCH_STARTED), por isso ficam em inglês.
 */
public enum TipoEvento {
    MATCH_STARTED,
    HALF_TIME,
    SECOND_HALF_STARTED,
    EXTRA_TIME_STARTED,
    PENALTIES_STARTED,
    MATCH_ENDED,
    MATCH_SUSPENDED,
    MATCH_POSTPONED,
    MATCH_CANCELLED,
    GOAL,
    OWN_GOAL,
    PENALTY_GOAL,
    MISSED_PENALTY,
    YELLOW_CARD,
    RED_CARD,
    SUBSTITUTION,
    VAR_DECISION;

    /** Ciclo de vida da partida (início, intervalo, fim...) vs. lance de jogo (gol, cartão...). */
    public boolean isCicloDeVida() {
        return switch (this) {
            case GOAL, OWN_GOAL, PENALTY_GOAL, MISSED_PENALTY,
                 YELLOW_CARD, RED_CARD, SUBSTITUTION, VAR_DECISION -> false;
            default -> true;
        };
    }

    /** Traduz o par type/detail da linha do tempo da API-Football. */
    public static TipoEvento deEventoApi(String type, String detail) {
        if (type == null) {
            return null;
        }
        String detalhe = detail == null ? "" : detail.toLowerCase();
        return switch (type.toLowerCase()) {
            case "goal" -> switch (detalhe) {
                case "own goal" -> OWN_GOAL;
                case "penalty" -> PENALTY_GOAL;
                case "missed penalty" -> MISSED_PENALTY;
                default -> GOAL;
            };
            case "card" -> detalhe.contains("red") ? RED_CARD : YELLOW_CARD;
            case "subst" -> SUBSTITUTION;
            case "var" -> VAR_DECISION;
            default -> null;
        };
    }
}
