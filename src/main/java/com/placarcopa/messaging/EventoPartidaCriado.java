package com.placarcopa.messaging;

import com.placarcopa.domain.EventoPartida;

/** Evento de aplicação disparado pela ingestão a cada evento de partida novo persistido. */
public record EventoPartidaCriado(EventoPartida evento) {
}
