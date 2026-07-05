package com.placarcopa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evento de partida — a unidade central do nosso modelo. Não guardamos placar:
 * ele e as demais estatísticas são calculados a partir da sequência de eventos.
 *
 * A estrutura espelha a mensagem que será publicada via Kafka:
 * match, team_A, team_B, competition{title, stage}, event, minute, sequence, payload.
 */
@Entity
@Table(name = "eventos_partida",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_code", "seq"}))
public class EventoPartida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificador de negócio da partida, ex.: ENG-GAN-13-07-2026. */
    @Column(name = "match_code", nullable = false)
    private String matchCode;

    @Column(name = "team_a", nullable = false)
    private String teamA;

    @Column(name = "team_b", nullable = false)
    private String teamB;

    /** Ex.: world-cup-2026. */
    private String competitionTitle;

    /** Ex.: groups_stage, round_of_16, final. */
    private String competitionStage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoEvento event;

    @Column(name = "match_minute")
    private Integer minute;

    /** Ordem do evento dentro da partida, começando em 1. */
    @Column(name = "seq", nullable = false)
    private Integer sequence;

    @Convert(converter = PayloadJsonConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> payload = new LinkedHashMap<>();

    /** Correlação interna com o data provider (id do fixture na API-Football). */
    @Column(nullable = false)
    private Long apiFixtureId;

    @Column(nullable = false)
    private Instant criadoEm = Instant.now();

    protected EventoPartida() {
    }

    public EventoPartida(String matchCode, String teamA, String teamB,
                         String competitionTitle, String competitionStage,
                         TipoEvento event, Integer minute, Integer sequence,
                         Map<String, Object> payload, Long apiFixtureId) {
        this.matchCode = matchCode;
        this.teamA = teamA;
        this.teamB = teamB;
        this.competitionTitle = competitionTitle;
        this.competitionStage = competitionStage;
        this.event = event;
        this.minute = minute;
        this.sequence = sequence;
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
        this.apiFixtureId = apiFixtureId;
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

    public TipoEvento getEvent() {
        return event;
    }

    public Integer getMinute() {
        return minute;
    }

    public Integer getSequence() {
        return sequence;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Long getApiFixtureId() {
        return apiFixtureId;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
