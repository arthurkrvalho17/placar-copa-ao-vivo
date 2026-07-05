package com.placarcopa.stats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class RedisConfig {

    /** Canal pub/sub em que cada atualização de placar é publicada. */
    public static final String CANAL_ATUALIZACOES = "placares:atualizacoes";

    @Bean
    public Jackson2JsonRedisSerializer<PlacarAoVivo> placarSerializer() {
        return new Jackson2JsonRedisSerializer<>(PlacarAoVivo.class);
    }

    @Bean
    public RedisTemplate<String, PlacarAoVivo> placarRedisTemplate(
            RedisConnectionFactory connectionFactory,
            Jackson2JsonRedisSerializer<PlacarAoVivo> placarSerializer) {
        RedisTemplate<String, PlacarAoVivo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(placarSerializer);
        template.setDefaultSerializer(placarSerializer);
        return template;
    }

    /** Escuta o canal de atualizações — é assim que o web server fica sabendo, em tempo real, de cada evento novo. */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            PlacarUpdateSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CANAL_ATUALIZACOES));
        return container;
    }
}
