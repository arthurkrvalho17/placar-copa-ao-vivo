package com.placarcopa.messaging;

import com.placarcopa.repository.EventoPartidaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@Component
public class MatchEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MatchEventPublisher.class);

    private final KafkaTemplate<String, MatchEventMessage> kafkaTemplate;
    private final EventoPartidaRepository eventoRepository;

    /**
     * Confirmações rodam fora da thread de rede do producer: o callback do send
     * executa na sender thread única do Kafka, e um UPDATE bloqueante ali
     * travaria todos os envios da aplicação atrás de latência de banco.
     */
    private final ExecutorService confirmacoes = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("confirma-publicacao-", 0).factory());

    public MatchEventPublisher(KafkaTemplate<String, MatchEventMessage> kafkaTemplate,
                               EventoPartidaRepository eventoRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventoRepository = eventoRepository;
    }

    /**
     * Publica no tópico chaveado pelo match e confirma no banco quando o broker
     * aceita. Falhas (do envio ou da confirmação) ficam com publicado=false e o
     * republicador reenvia — entrega at-least-once, os consumers deduplicam
     * pela sequence.
     */
    public void publicar(MatchEventMessage mensagem, Long eventoId) {
        kafkaTemplate.send(KafkaConfig.TOPICO_EVENTOS_PARTIDA, mensagem.match(), mensagem)
                .whenComplete((resultado, erro) -> {
                    if (erro != null) {
                        log.error("Falha ao publicar evento {} seq {} da partida {} (sera reenviado): {}",
                                mensagem.event(), mensagem.sequence(), mensagem.match(), erro.getMessage());
                        return;
                    }
                    confirmarPublicacao(mensagem, eventoId);
                    log.debug("Evento {} seq {} da partida {} publicado em {}-{}",
                            mensagem.event(), mensagem.sequence(), mensagem.match(),
                            resultado.getRecordMetadata().partition(), resultado.getRecordMetadata().offset());
                });
    }

    private void confirmarPublicacao(MatchEventMessage mensagem, Long eventoId) {
        try {
            confirmacoes.execute(() -> {
                try {
                    eventoRepository.marcarPublicado(eventoId);
                } catch (RuntimeException e) {
                    log.warn("Falha ao confirmar publicacao do evento {} seq {} da partida {} "
                                    + "(republicador reenviara): {}",
                            mensagem.event(), mensagem.sequence(), mensagem.match(), e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // shutdown em andamento — o evento fica pendente e o republicador cobre
            log.debug("Confirmacao do evento {} descartada durante shutdown", eventoId);
        }
    }

    @PreDestroy
    void encerrar() {
        confirmacoes.shutdown();
    }
}
