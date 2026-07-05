package com.placarcopa.messaging;

import com.placarcopa.stats.PlacarAoVivoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group responsável pelas consultas rápidas do frontend
 * (placar, cartões, status), mantidas em memória para leitura imediata.
 */
@Component
public class ConsultasRapidasConsumer {

    public static final String GROUP_ID = "placar-consultas-rapidas";

    private static final Logger log = LoggerFactory.getLogger(ConsultasRapidasConsumer.class);

    private final PlacarAoVivoService placarAoVivoService;

    public ConsultasRapidasConsumer(PlacarAoVivoService placarAoVivoService) {
        this.placarAoVivoService = placarAoVivoService;
    }

    @KafkaListener(topics = KafkaConfig.TOPICO_EVENTOS_PARTIDA, groupId = GROUP_ID)
    public void consumir(MatchEventMessage mensagem) {
        log.debug("[{}] {} seq {} da partida {}",
                GROUP_ID, mensagem.event(), mensagem.sequence(), mensagem.match());
        placarAoVivoService.aplicar(mensagem);
    }
}
