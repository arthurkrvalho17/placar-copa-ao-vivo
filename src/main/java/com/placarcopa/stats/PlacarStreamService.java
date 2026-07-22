package com.placarcopa.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Mantém as conexões SSE dos clientes do frontend e retransmite cada
 * atualização recebida via Redis pub/sub.
 *
 * Cada assinante tem sua própria fila de envio (thread virtual): o envio SSE é
 * I/O bloqueante, e sem isso um cliente lento travaria a thread única do
 * dispatcher do Redis e congelaria o tempo real de todos os outros.
 */
@Service
public class PlacarStreamService {

    private static final Logger log = LoggerFactory.getLogger(PlacarStreamService.class);
    private static final long SEM_TIMEOUT = 0L;

    private final Map<SseEmitter, ExecutorService> inscritos = new ConcurrentHashMap<>();

    /**
     * Registra o assinante ANTES de tirar o snapshot: atualizações que chegarem
     * no meio entram na fila atrás do snapshot — nada se perde.
     */
    public SseEmitter inscrever(Supplier<List<PlacarAoVivo>> snapshot) {
        SseEmitter emitter = new SseEmitter(SEM_TIMEOUT);
        ExecutorService fila = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("sse-placar-", 0).factory());
        inscritos.put(emitter, fila);

        Runnable remover = () -> remover(emitter);
        emitter.onCompletion(remover);
        emitter.onTimeout(remover);
        emitter.onError(e -> remover(emitter));

        executarNaFila(emitter, fila, () -> {
            try {
                for (PlacarAoVivo placar : snapshot.get()) {
                    if (!enviar(emitter, placar)) {
                        remover(emitter);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                // Snapshot falhou (ex.: Redis fora): encerra com erro para o
                // EventSource do cliente reconectar, em vez de pendurar o stream
                log.warn("Falha no snapshot inicial de um assinante SSE: {}", e.getMessage());
                remover(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (RuntimeException ignorada) {
                    // emitter já finalizado pelo container
                }
            }
        });
        return emitter;
    }

    public void transmitir(PlacarAoVivo placar) {
        inscritos.forEach((emitter, fila) -> executarNaFila(emitter, fila, () -> {
            if (!enviar(emitter, placar)) {
                remover(emitter);
            }
        }));
    }

    /**
     * Um assinante pode ser removido (e sua fila desligada) entre o forEach e o
     * execute — a rejeição não pode derrubar a entrega aos demais assinantes
     * nem vazar para a thread do dispatcher do Redis.
     */
    private void executarNaFila(SseEmitter emitter, ExecutorService fila, Runnable tarefa) {
        try {
            fila.execute(tarefa);
        } catch (RejectedExecutionException e) {
            remover(emitter);
        }
    }

    public int inscritosAtivos() {
        return inscritos.size();
    }

    private void remover(SseEmitter emitter) {
        ExecutorService fila = inscritos.remove(emitter);
        if (fila != null) {
            fila.shutdown();
        }
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
