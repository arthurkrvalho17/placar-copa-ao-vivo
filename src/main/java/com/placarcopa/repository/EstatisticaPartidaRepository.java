package com.placarcopa.repository;

import com.placarcopa.domain.EstatisticaPartida;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EstatisticaPartidaRepository extends JpaRepository<EstatisticaPartida, Long> {

    Optional<EstatisticaPartida> findByMatchCode(String matchCode);
}
