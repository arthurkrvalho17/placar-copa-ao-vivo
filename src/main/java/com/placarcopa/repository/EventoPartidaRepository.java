package com.placarcopa.repository;

import com.placarcopa.domain.EventoPartida;
import com.placarcopa.domain.TipoEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface EventoPartidaRepository extends JpaRepository<EventoPartida, Long> {

    List<EventoPartida> findByApiFixtureIdOrderBySequenceAsc(Long apiFixtureId);

    List<EventoPartida> findByMatchCodeOrderBySequenceAsc(String matchCode);

    /** Eventos que faltam numa projeção: tudo o que veio depois da última sequence aplicada. */
    List<EventoPartida> findByMatchCodeAndSequenceGreaterThanOrderBySequenceAsc(String matchCode, int sequence);

    /** Detecta colisão de match code entre fixtures diferentes na mesma data. */
    boolean existsByMatchCodeAndApiFixtureIdNot(String matchCode, Long apiFixtureId);

    /** Eventos cuja publicação no Kafka ainda não foi confirmada, dentro da janela de reenvio. */
    List<EventoPartida> findTop200ByPublicadoFalseAndCriadoEmBetweenOrderByMatchCodeAscSequenceAsc(
            Instant inicio, Instant fim);

    /** Pendentes velhos demais para reenvio automático — só visibilidade. */
    long countByPublicadoFalseAndCriadoEmBefore(Instant limite);

    @Transactional
    @Modifying
    @Query("update EventoPartida e set e.publicado = true where e.id = :id")
    void marcarPublicado(@Param("id") Long id);

    /**
     * Fixtures que começaram mas ainda não têm evento terminal, com o instante
     * em que foram vistas pela primeira vez ([0]=apiFixtureId, [1]=Instant).
     */
    @Query("""
            select e.apiFixtureId, min(e.criadoEm) from EventoPartida e
            where e.event = :iniciado
              and not exists (
                select 1 from EventoPartida t
                where t.apiFixtureId = e.apiFixtureId and t.event in :terminais)
            group by e.apiFixtureId
            """)
    List<Object[]> findPartidasEmAbertoComInicio(@Param("iniciado") TipoEvento iniciado,
                                                 @Param("terminais") Collection<TipoEvento> terminais);
}
