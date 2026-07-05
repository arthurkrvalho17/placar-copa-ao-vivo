package com.placarcopa.repository;

import com.placarcopa.domain.EventoPartida;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventoPartidaRepository extends JpaRepository<EventoPartida, Long> {

    List<EventoPartida> findByApiFixtureIdOrderBySequenceAsc(Long apiFixtureId);

    List<EventoPartida> findByMatchCodeOrderBySequenceAsc(String matchCode);
}
