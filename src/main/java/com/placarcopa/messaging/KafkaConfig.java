package com.placarcopa.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * Eventos de partida, chaveados pelo match code — mesma partida sempre cai
     * na mesma partição, preservando a ordem dos eventos de cada jogo.
     */
    public static final String TOPICO_EVENTOS_PARTIDA = "partidas.eventos";

    /** Mensagens-veneno vão para cá após esgotar os retries, em vez de serem descartadas. */
    public static final String TOPICO_DLT = TOPICO_EVENTOS_PARTIDA + ".dlt";

    @Bean
    public NewTopic topicoEventosPartida() {
        return TopicBuilder.name(TOPICO_EVENTOS_PARTIDA)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic topicoEventosPartidaDlt() {
        return TopicBuilder.name(TOPICO_DLT)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Após 3 tentativas com 1s de intervalo, a mensagem vai para o DLT com os
     * headers de diagnóstico — nada é descartado silenciosamente.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, MatchEventMessage> matchEventKafkaTemplate) {
        @SuppressWarnings("unchecked")
        KafkaOperations<Object, Object> operations =
                (KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) matchEventKafkaTemplate;
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(operations,
                (registro, erro) -> new TopicPartition(TOPICO_DLT, registro.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
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
