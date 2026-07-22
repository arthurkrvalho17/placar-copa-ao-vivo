package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.ApiFootballQuotaExhaustedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionSchedulerTest {

    private IngestionService service;
    private IngestionStatus status;
    private IngestionScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(IngestionService.class);
        status = new IngestionStatus();
        scheduler = new IngestionScheduler(service, status, List.of());
    }

    @Test
    void deveRegistrarSucessoAposIngestaoBemSucedida() {
        when(service.ingerirAoVivo(List.of())).thenReturn(new IngestionSummary(1, 1));

        scheduler.ingerirAoVivo();

        assertThat(status.atual().cotaEsgotada()).isFalse();
        assertThat(status.atual().ultimoSucessoEm()).isNotNull();
    }

    @Test
    void deveEntrarEmBackoffQuandoCotaEsgotaENaoTentarNovamenteAntesDaJanela() {
        when(service.ingerirAoVivo(List.of()))
                .thenThrow(new ApiFootballQuotaExhaustedException("cota esgotada"));

        scheduler.ingerirAoVivo(); // 1ª tentativa: esgota e entra em backoff
        scheduler.ingerirAoVivo(); // tick seguinte: deve ser pulado, ainda dentro da janela

        verify(service, times(1)).ingerirAoVivo(List.of());
        assertThat(status.atual().cotaEsgotada()).isTrue();
        assertThat(status.atual().proximaTentativaEm()).isAfter(java.time.Instant.now());
    }

    @Test
    void sucessoAposCotaEsgotadaLimpaOStatus() {
        when(service.ingerirAoVivo(List.of()))
                .thenThrow(new ApiFootballQuotaExhaustedException("cota esgotada"))
                .thenReturn(new IngestionSummary(1, 1));

        scheduler.ingerirAoVivo(); // esgota
        // simula a janela de backoff já ter passado, chamando de novo diretamente
        // (o teste de skip já cobre o comportamento dentro da janela)
        forcarProximaTentativaAgora();
        scheduler.ingerirAoVivo(); // sucesso

        assertThat(status.atual().cotaEsgotada()).isFalse();
        assertThat(status.atual().ultimoSucessoEm()).isNotNull();
    }

    @Test
    void falhaTransitoriaNaoAtivaBackoff() {
        when(service.ingerirAoVivo(List.of()))
                .thenThrow(new RuntimeException("timeout de rede"))
                .thenReturn(new IngestionSummary(1, 1));

        scheduler.ingerirAoVivo(); // falha pontual
        scheduler.ingerirAoVivo(); // deve tentar de novo imediatamente, sem backoff

        verify(service, times(2)).ingerirAoVivo(List.of());
        assertThat(status.atual().cotaEsgotada()).isFalse();
    }

    private void forcarProximaTentativaAgora() {
        try {
            var campo = IngestionScheduler.class.getDeclaredField("proximaTentativaPermitida");
            campo.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ref = (java.util.concurrent.atomic.AtomicReference<java.time.Instant>) campo.get(scheduler);
            ref.set(java.time.Instant.now().minusSeconds(1));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
