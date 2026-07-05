package com.placarcopa.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mantém as conexões SSE dos clientes do frontend e retransmite cada
 * atualização de placar recebida via Redis pub/sub.
 */
@Service
public class PlacarStreamService {

    private static final Logger log = LoggerFactory.getLogger(PlacarStreamService.class);
    private static final long SEM_TIMEOUT = 0L;

    private final List<SseEmitter> inscritos = new CopyOnWriteArrayList<>();

    public SseEmitter inscrever(List<PlacarAoVivo> snapshotInicial) {
        SseEmitter emitter = new SseEmitter(SEM_TIMEOUT);
        inscritos.add(emitter);
        Runnable remover = () -> inscritos.remove(emitter);
        emitter.onCompletion(remover);
        emitter.onTimeout(remover);
        emitter.onError(e -> remover.run());

        for (PlacarAoVivo placar : snapshotInicial) {
            if (!enviar(emitter, placar)) {
                break;
            }
        }
        return emitter;
    }

    public void transmitir(PlacarAoVivo placar) {
        for (SseEmitter emitter : inscritos) {
            if (!enviar(emitter, placar)) {
                inscritos.remove(emitter);
            }
        }
    }

    public int inscritosAtivos() {
        return inscritos.size();
    }

    private boolean enviar(SseEmitter emitter, PlacarAoVivo placar) {
        try {
            emitter.send(SseEmitter.event()
                    .name("placar")
                    .data(placar, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Removendo inscrito SSE desconectado: {}", e.getMessage());
            return false;
        }
    }
}
