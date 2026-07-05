package com.placarcopa.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.stereotype.Component;

/**
 * Assinante do canal Redis pub/sub: é o "ouvido" do web server. Cada mensagem
 * publicada no canal vira um push imediato para os clientes SSE conectados.
 */
@Component
public class PlacarUpdateSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(PlacarUpdateSubscriber.class);

    private final Jackson2JsonRedisSerializer<PlacarAoVivo> serializer;
    private final PlacarStreamService streamService;

    public PlacarUpdateSubscriber(Jackson2JsonRedisSerializer<PlacarAoVivo> placarSerializer,
                                  PlacarStreamService streamService) {
        this.serializer = placarSerializer;
        this.streamService = streamService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        PlacarAoVivo placar = serializer.deserialize(message.getBody());
        if (placar == null) {
            return;
        }
        log.debug("Atualização recebida via pub/sub: {} seq {}", placar.match(), placar.ultimaSequence());
        streamService.transmitir(placar);
    }
}
