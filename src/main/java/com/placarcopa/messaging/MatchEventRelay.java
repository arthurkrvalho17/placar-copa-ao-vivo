package com.placarcopa.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publica no Kafka somente após o commit da transação de ingestão —
 * evento que sofreu rollback nunca chega ao tópico.
 */
@Component
public class MatchEventRelay {

    private final MatchEventPublisher publisher;

    public MatchEventRelay(MatchEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoCriarEventoDePartida(EventoPartidaCriado criado) {
        publisher.publicar(MatchEventMessage.de(criado.evento()));
    }
}
