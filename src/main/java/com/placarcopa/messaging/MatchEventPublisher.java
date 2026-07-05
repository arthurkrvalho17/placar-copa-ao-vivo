package com.placarcopa.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class MatchEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MatchEventPublisher.class);

    private final KafkaTemplate<String, MatchEventMessage> kafkaTemplate;

    public MatchEventPublisher(KafkaTemplate<String, MatchEventMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publicar(MatchEventMessage mensagem) {
        kafkaTemplate.send(KafkaConfig.TOPICO_EVENTOS_PARTIDA, mensagem.match(), mensagem)
                .whenComplete((resultado, erro) -> {
                    if (erro != null) {
                        log.error("Falha ao publicar evento {} seq {} da partida {}: {}",
                                mensagem.event(), mensagem.sequence(), mensagem.match(), erro.getMessage());
                    } else {
                        log.debug("Evento {} seq {} da partida {} publicado em {}-{}",
                                mensagem.event(), mensagem.sequence(), mensagem.match(),
                                resultado.getRecordMetadata().partition(), resultado.getRecordMetadata().offset());
                    }
                });
    }
}
