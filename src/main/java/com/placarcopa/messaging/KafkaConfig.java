package com.placarcopa.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaConfig {

    /**
     * Eventos de partida, chaveados pelo match code — mesma partida sempre cai
     * na mesma partição, preservando a ordem dos eventos de cada jogo.
     */
    public static final String TOPICO_EVENTOS_PARTIDA = "partidas.eventos";

    @Bean
    public NewTopic topicoEventosPartida() {
        return TopicBuilder.name(TOPICO_EVENTOS_PARTIDA)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Template tipado para as mensagens de evento de partida — o auto-configurado
     * do Boot é KafkaTemplate&lt;Object, Object&gt; e não casa com a injeção tipada.
     */
    @Bean
    public KafkaTemplate<String, MatchEventMessage> matchEventKafkaTemplate(
            ProducerFactory<String, MatchEventMessage> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
