package com.placarcopa.domain;

import com.placarcopa.messaging.MatchEventMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Estatísticas gerais de uma partida, acumuladas exclusivamente a partir dos
 * eventos consumidos do Kafka (consumer group de estatísticas).
 */
@Entity
@Table(name = "estatisticas_partida")
public class EstatisticaPartida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_code", nullable = false, unique = true)
    private String matchCode;

    @Column(name = "team_a", nullable = false)
    private String teamA;

    @Column(name = "team_b", nullable = false)
    private String teamB;

    private String competitionTitle;

    private String competitionStage;

    private int golsTeamA;
    private int golsTeamB;
    private int cartoesAmarelosTeamA;
    private int cartoesAmarelosTeamB;
    private int cartoesVermelhosTeamA;
    private int cartoesVermelhosTeamB;
    private int substituicoesTeamA;
    private int substituicoesTeamB;
    private int penaltisPerdidosTeamA;
    private int penaltisPerdidosTeamB;

    /** Último evento de ciclo de vida aplicado (MATCH_STARTED, HALF_TIME...). */
    private String status;

    private String minuto;

    @Column(name = "ultima_seq", nullable = false)
    private int ultimaSequence;

    @Column(nullable = false)
    private Instant atualizadoEm = Instant.now();

    protected EstatisticaPartida() {
    }

    public EstatisticaPartida(String matchCode, String teamA, String teamB,
                              String competitionTitle, String competitionStage) {
        this.matchCode = matchCode;
        this.teamA = teamA;
        this.teamB = teamB;
        this.competitionTitle = competitionTitle;
        this.competitionStage = competitionStage;
    }

    /**
     * Aplica um evento e devolve true se houve mudança.
     * Mensagens com sequence já aplicado são ignoradas (entrega at-least-once).
     */
    public boolean aplicar(MatchEventMessage mensagem) {
        if (mensagem.sequence() <= ultimaSequence) {
            return false;
        }
        Object time = mensagem.payload() == null ? null : mensagem.payload().get("team");
        boolean doTimeA = teamA.equals(time);
        boolean doTimeB = teamB.equals(time);

        switch (mensagem.event()) {
            case GOAL, PENALTY_GOAL -> {
                if (doTimeA) {
                    golsTeamA++;
                } else if (doTimeB) {
                    golsTeamB++;
                }
            }
            case OWN_GOAL -> {
                if (doTimeA) {
                    golsTeamB++;
                } else if (doTimeB) {
                    golsTeamA++;
                }
            }
            case MISSED_PENALTY -> {
                if (doTimeA) {
                    penaltisPerdidosTeamA++;
                } else if (doTimeB) {
                    penaltisPerdidosTeamB++;
                }
            }
            case YELLOW_CARD -> {
                if (doTimeA) {
                    cartoesAmarelosTeamA++;
                } else if (doTimeB) {
                    cartoesAmarelosTeamB++;
                }
            }
            case RED_CARD -> {
                if (doTimeA) {
                    cartoesVermelhosTeamA++;
                } else if (doTimeB) {
                    cartoesVermelhosTeamB++;
                }
            }
            case SUBSTITUTION -> {
                if (doTimeA) {
                    substituicoesTeamA++;
                } else if (doTimeB) {
                    substituicoesTeamB++;
                }
            }
            default -> {
                // VAR e eventos de ciclo de vida não somam estatística
            }
        }

        if (mensagem.event().isCicloDeVida()) {
            status = mensagem.event().name();
        }
        if (mensagem.minute() != null) {
            minuto = mensagem.minute();
        }
        ultimaSequence = mensagem.sequence();
        atualizadoEm = Instant.now();
        return true;
    }

    public Long getId() {
        return id;
    }

    public String getMatchCode() {
        return matchCode;
    }

    public String getTeamA() {
        return teamA;
    }

    public String getTeamB() {
        return teamB;
    }

    public String getCompetitionTitle() {
        return competitionTitle;
    }

    public String getCompetitionStage() {
        return competitionStage;
    }

    public int getGolsTeamA() {
        return golsTeamA;
    }

    public int getGolsTeamB() {
        return golsTeamB;
    }

    public int getCartoesAmarelosTeamA() {
        return cartoesAmarelosTeamA;
    }

    public int getCartoesAmarelosTeamB() {
        return cartoesAmarelosTeamB;
    }

    public int getCartoesVermelhosTeamA() {
        return cartoesVermelhosTeamA;
    }

    public int getCartoesVermelhosTeamB() {
        return cartoesVermelhosTeamB;
    }

    public int getSubstituicoesTeamA() {
        return substituicoesTeamA;
    }

    public int getSubstituicoesTeamB() {
        return substituicoesTeamB;
    }

    public int getPenaltisPerdidosTeamA() {
        return penaltisPerdidosTeamA;
    }

    public int getPenaltisPerdidosTeamB() {
        return penaltisPerdidosTeamB;
    }

    public String getStatus() {
        return status;
    }

    public String getMinuto() {
        return minuto;
    }

    public int getUltimaSequence() {
        return ultimaSequence;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }
}
