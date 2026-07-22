package com.placarcopa.dataprovider;

import com.placarcopa.dataprovider.dto.ApiFootballResponse;
import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.dataprovider.dto.MatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DataProvider da aplicação: consome a API-Football (v3).
 *
 * A API só aceita GET, autentica pelo header {@code x-apisports-key} e o plano
 * define a cota diária/por minuto — os dados ao vivo são atualizados a cada 15s
 * e a recomendação oficial é no máximo 1 chamada por minuto por recurso ao
 * vivo.
 */
@Component
public class ApiFootballClient {

    public static final String HEADER_API_KEY = "x-apisports-key";
    static final String HEADER_QUOTA_DIARIA_RESTANTE = "x-ratelimit-requests-remaining";

    private static final Logger log = LoggerFactory.getLogger(ApiFootballClient.class);

    private final RestClient restClient;

    public ApiFootballClient(RestClient apiFootballRestClient) {
        this.restClient = apiFootballRestClient;
    }

    /** Todos os jogos em andamento no momento (a resposta já inclui os eventos). */
    public List<FixtureResponse> buscarJogosAoVivo() {
        return buscarFixtures(uri -> uri.path("/fixtures").queryParam("live", "all").build());
    }

    /**
     * Jogos em andamento apenas das ligas informadas (ex.: id da Copa do Mundo).
     */
    public List<FixtureResponse> buscarJogosAoVivoPorLigas(List<Long> ligaIds) {
        String ligas = ligaIds.stream().map(String::valueOf).collect(Collectors.joining("-"));
        return buscarFixtures(uri -> uri.path("/fixtures").queryParam("live", ligas).build());
    }

    /**
     * Todos os jogos de uma liga/copa em uma temporada (agendados, ao vivo e
     * encerrados).
     */
    public List<FixtureResponse> buscarJogosPorLigaETemporada(long ligaId, int temporada) {
        return buscarFixtures(uri -> uri.path("/fixtures")
                .queryParam("league", ligaId)
                .queryParam("season", temporada)
                .build());
    }

    /** Um jogo específico; a resposta inclui eventos, escalações e estatísticas. */
    public List<FixtureResponse> buscarJogoPorId(long fixtureId) {
        return buscarFixtures(uri -> uri.path("/fixtures").queryParam("id", fixtureId).build());
    }

    /**
     * Vários jogos pelo id, em lotes de até 20 (limite da API). Usado para
     * reconciliar partidas que saíram do feed ao vivo sem desfecho conhecido.
     */
    public List<FixtureResponse> buscarJogosPorIds(List<Long> fixtureIds) {
        List<FixtureResponse> resultado = new java.util.ArrayList<>();
        for (int i = 0; i < fixtureIds.size(); i += 20) {
            List<Long> lote = fixtureIds.subList(i, Math.min(i + 20, fixtureIds.size()));
            String ids = lote.stream().map(String::valueOf).collect(Collectors.joining("-"));
            resultado.addAll(buscarFixtures(uri -> uri.path("/fixtures").queryParam("ids", ids).build()));
        }
        return resultado;
    }

    /** Linha do tempo de eventos de um jogo (gols, cartões, substituições, VAR). */
    public List<MatchEvent> buscarEventos(long fixtureId) {
        return executar(
                uri -> uri.path("/fixtures/events").queryParam("fixture", fixtureId).build(),
                new ParameterizedTypeReference<ApiFootballResponse<MatchEvent>>() {
                });
    }

    private List<FixtureResponse> buscarFixtures(Function<UriBuilder, URI> uri) {
        return executar(uri, new ParameterizedTypeReference<ApiFootballResponse<FixtureResponse>>() {
        });
    }

    private <T> List<T> executar(Function<UriBuilder, URI> uri,
            ParameterizedTypeReference<ApiFootballResponse<T>> tipo) {
        ApiFootballResponse<T> body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> {
                        registrarQuota(response.getHeaders().getFirst(HEADER_QUOTA_DIARIA_RESTANTE));
                        if (response.getStatusCode().isError()) {
                            throw new ApiFootballException("API-Football respondeu HTTP "
                                    + response.getStatusCode().value() + " para " + request.getURI());
                        }
                        return response.bodyTo(tipo);
                    });
        } catch (RestClientException e) {
            throw new ApiFootballException("Falha ao chamar a API-Football: " + e.getMessage(), e);
        }

        // 204 No Content (documentado pela API) e corpo vazio significam "sem
        // resultados" — não pode virar exceção, senão o poll com feed vazio
        // aborta e a reconciliação de partidas encerradas nunca roda. Fica o
        // aviso: um feed vazio espúrio alimenta a reconciliação como se todas
        // as partidas abertas tivessem saído do ar.
        if (body == null) {
            log.warn("API-Football devolveu corpo vazio; tratando como zero resultados");
            return List.of();
        }
        if (body.temErros()) {
            if (body.errors().has("requests")) {
                throw new ApiFootballQuotaExhaustedException(
                        "Cota da API-Football esgotada: " + body.errors().get("requests").asText());
            }
            throw new ApiFootballException("API-Football retornou erros: " + body.descricaoErros());
        }
        return body.response() == null ? List.of() : body.response();
    }

    private void registrarQuota(String restante) {
        if (restante != null) {
            log.debug("API-Football: {} requisições restantes na cota diária", restante);
        }
    }
}
