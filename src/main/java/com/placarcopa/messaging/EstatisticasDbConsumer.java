package com.placarcopa.messaging;

import com.placarcopa.stats.EstatisticaPartidaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group responsável pelo banco de estatísticas gerais das partidas.
 * Lê o tópico integralmente, de forma independente do consumer de consultas rápidas.
 */
@Component
public class EstatisticasDbConsumer {

    public static final String GROUP_ID = "placar-estatisticas-db";

    private static final Logger log = LoggerFactory.getLogger(EstatisticasDbConsumer.class);

    private final EstatisticaPartidaService estatisticaService;

    public EstatisticasDbConsumer(EstatisticaPartidaService estatisticaService) {
        this.estatisticaService = estatisticaService;
    }

    @KafkaListener(topics = KafkaConfig.TOPICO_EVENTOS_PARTIDA, groupId = GROUP_ID)
    public void consumir(MatchEventMessage mensagem) {
        log.debug("[{}] {} seq {} da partida {}",
                GROUP_ID, mensagem.event(), mensagem.sequence(), mensagem.match());
        estatisticaService.aplicar(mensagem);
    }
}
