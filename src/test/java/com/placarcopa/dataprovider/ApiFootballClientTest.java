package com.placarcopa.dataprovider;

import com.placarcopa.dataprovider.dto.FixtureResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ApiFootballClientTest {

    private static final String BASE_URL = "https://v3.football.api-sports.io";

    private MockRestServiceServer server;
    private ApiFootballClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(ApiFootballClient.HEADER_API_KEY, "chave-teste");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ApiFootballClient(builder.build());
    }

    @Test
    void deveBuscarJogosAoVivoComHeaderDeAutenticacao() {
        server.expect(requestTo(BASE_URL + "/fixtures?live=all"))
                .andExpect(header(ApiFootballClient.HEADER_API_KEY, "chave-teste"))
                .andRespond(withSuccess(FIXTURES_LIVE_JSON, MediaType.APPLICATION_JSON));

        List<FixtureResponse> jogos = client.buscarJogosAoVivo();

        assertThat(jogos).hasSize(1);
        FixtureResponse jogo = jogos.get(0);
        assertThat(jogo.fixture().id()).isEqualTo(239625L);
        assertThat(jogo.fixture().status().codigo()).isEqualTo("HT");
        assertThat(jogo.fixture().status().elapsed()).isEqualTo(45);
        assertThat(jogo.teams().home().name()).isEqualTo("Rapide Oued ZEM");
        assertThat(jogo.teams().away().name()).isEqualTo("Wydad AC");
        assertThat(jogo.goals().home()).isZero();
        assertThat(jogo.goals().away()).isEqualTo(1);
        assertThat(jogo.score().halftime().away()).isEqualTo(1);
        assertThat(jogo.events()).hasSize(1);
        assertThat(jogo.events().get(0).type()).isEqualTo("Goal");
        assertThat(jogo.events().get(0).player().name()).isEqualTo("Ayoub El Amloud");
    }

    @Test
    void deveLancarExcecaoQuandoApiRetornaErros() {
        server.expect(requestTo(BASE_URL + "/fixtures?live=all"))
                .andRespond(withSuccess(ERRO_TOKEN_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.buscarJogosAoVivo())
                .isInstanceOf(ApiFootballException.class)
                .hasMessageContaining("token");
    }

    @Test
    void deveLancarExcecaoQuandoHttpFalha() {
        server.expect(requestTo(BASE_URL + "/fixtures?live=all"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.buscarJogosAoVivo())
                .isInstanceOf(ApiFootballException.class)
                .hasMessageContaining("500");
    }

    // Estrutura conforme o exemplo oficial da documentação (fixtures?live=all)
    private static final String FIXTURES_LIVE_JSON = """
            {
              "get": "fixtures",
              "parameters": {"live": "all"},
              "errors": [],
              "results": 1,
              "paging": {"current": 1, "total": 1},
              "response": [
                {
                  "fixture": {
                    "id": 239625,
                    "referee": null,
                    "timezone": "UTC",
                    "date": "2020-02-06T14:00:00+00:00",
                    "timestamp": 1580997600,
                    "periods": {"first": 1580997600, "second": null},
                    "venue": {"id": 1887, "name": "Stade Municipal", "city": "Oued Zem"},
                    "status": {"long": "Halftime", "short": "HT", "elapsed": 45, "extra": null}
                  },
                  "league": {
                    "id": 200,
                    "name": "Botola Pro",
                    "country": "Morocco",
                    "logo": "https://media.api-sports.io/football/leagues/115.png",
                    "flag": "https://media.api-sports.io/flags/ma.svg",
                    "season": 2019,
                    "round": "Regular Season - 14"
                  },
                  "teams": {
                    "home": {"id": 967, "name": "Rapide Oued ZEM", "logo": "https://media.api-sports.io/football/teams/967.png", "winner": false},
                    "away": {"id": 968, "name": "Wydad AC", "logo": "https://media.api-sports.io/football/teams/968.png", "winner": true}
                  },
                  "goals": {"home": 0, "away": 1},
                  "score": {
                    "halftime": {"home": 0, "away": 1},
                    "fulltime": {"home": null, "away": null},
                    "extratime": {"home": null, "away": null},
                    "penalty": {"home": null, "away": null}
                  },
                  "events": [
                    {
                      "time": {"elapsed": 22, "extra": null},
                      "team": {"id": 968, "name": "Wydad AC", "logo": "https://media.api-sports.io/football/teams/968.png"},
                      "player": {"id": 2408, "name": "Ayoub El Amloud"},
                      "assist": {"id": null, "name": null},
                      "type": "Goal",
                      "detail": "Normal Goal",
                      "comments": null
                    }
                  ]
                }
              ]
            }
            """;

    private static final String ERRO_TOKEN_JSON = """
            {
              "get": "fixtures",
              "parameters": {"live": "all"},
              "errors": {"token": "Error/Missing application key."},
              "results": 0,
              "paging": {"current": 1, "total": 1},
              "response": []
            }
            """;
}
