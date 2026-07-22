# Placar Copa

API de placar de jogos de futebol em tempo real, construída em torno de **event sourcing** e **CQRS**: cada acontecimento de uma partida (início, gol, cartão, fim de jogo...) é tratado como um evento imutável, publicado no Kafka e projetado em diferentes modelos de leitura otimizados para cada consumidor — estatísticas históricas em PostgreSQL e placar instantâneo em Redis, empurrado ao frontend via SSE.

> Projeto de estudo/portfólio inspirado no problema de escala de sistemas como o placar ao vivo de uma Copa do Mundo.

## Índice

- [Especificação do projeto](#especificação-do-projeto)
  - [Requisitos funcionais](#requisitos-funcionais)
  - [Requisitos não funcionais](#requisitos-não-funcionais)
  - [Endpoints planejados](#endpoints-planejados)
- [Arquitetura](#arquitetura)
  - [Diagrama](#diagrama)
  - [Fluxo de dados](#fluxo-de-dados)
  - [Por que event sourcing](#por-que-event-sourcing)
  - [Por que Kafka particionado por partida](#por-que-kafka-particionado-por-partida)
  - [Consistência e garantias de entrega](#consistência-e-garantias-de-entrega)
- [Modelo de eventos](#modelo-de-eventos)
- [API HTTP](#api-http)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Como rodar](#como-rodar)
- [Testes](#testes)
- [Aderência aos requisitos e próximos passos](#aderência-aos-requisitos-e-próximos-passos)

---

## Especificação do projeto

Estas são as especificações originais definidas antes da implementação (documentos de análise do problema e de definição da API), preservadas aqui como registro de intenção do produto.

### Requisitos funcionais

1. **Resultado dos jogos** — os usuários devem poder visualizar o resultado dos jogos em tempo real e receber atualizações sem necessidade de atualizar a página ou aplicativo.
2. **Estatística/Histórico** — os usuários devem ser capazes de ver dados e estatísticas completas de jogos passados, times, campeonatos e também de jogadores específicos.

### Requisitos não funcionais

1. O sistema deve conseguir aguentar **10 milhões de usuários ativos simultaneamente** nos dias de grandes jogos.
2. O sistema deve operar em modo de **alta disponibilidade (24/7)**.
3. O sistema deve ter **consistência forte** e não pode haver **perda de dados**.

### Endpoints planejados

Da definição original da API — o desenho é propositalmente enxuto, com a ressalva de que mais endpoints surgiriam durante o desenvolvimento:

```
GET /matches/{match_id}
```

Retorna as informações do jogo. Na implementação atual esse contrato foi desdobrado em dois endpoints de leitura (ver [API HTTP](#api-http)), consequência direta do desenho CQRS: um para o placar rápido (Redis) e outro para as estatísticas duráveis (PostgreSQL). Um endpoint agregador único em `/matches/{match_id}` compondo as duas fontes é um candidato natural de camada de API Gateway, ainda não implementado.

---

## Arquitetura

### Diagrama

![Arquitetura do sistema](docs/arquitetura.png)

> Diagrama de arquitetura do projeto. Para exibi-lo aqui, salve a imagem em `docs/arquitetura.png` neste repositório (o arquivo precisa ser adicionado manualmente — veja a observação ao final desta resposta).

### Fluxo de dados

```
Data Provider (API-Football)
        │  poll a cada 60s (IngestionScheduler)
        ▼
Ingestion API (IngestionService + FixtureMapper)
        │  modela o fixture cru em eventos de negócio
        ▼
Event Store — PostgreSQL (EventoPartida, append-only)
        │  publica após commit (MatchEventRelay, at-least-once)
        ▼
Kafka — tópico "partidas.eventos" (3 partições, chave = match code)
        │
        ├──▶ Consumer Group "placar-estatisticas-db"  → PostgreSQL (EstatisticaPartida)
        │        estatísticas duráveis por partida (histórico)
        │
        └──▶ Consumer Group "placar-consultas-rapidas" → Redis (PlacarAoVivo)
                 placar/cartões instantâneos + PUBLISH no canal "placares:atualizacoes"
                        │
                        ▼
                 Web Server ouve o pub/sub (PlacarUpdateSubscriber)
                        │
                        ▼
                 SSE (/api/placares/stream) → Frontend
```

Cada camada é um serviço substituível e escalável de forma independente: a ingestão pode rodar em múltiplas instâncias atrás de um load balancer, cada consumer group escala conforme o número de partições do tópico, e o Redis atende exclusivamente ao caminho de leitura quente (baixa latência) sem jamais tocar no PostgreSQL.

### Por que event sourcing

O placar de uma partida nunca é armazenado como um número — ele é **derivado da sequência de eventos** (`MATCH_STARTED`, `GOAL`, `YELLOW_CARD`, `MATCH_ENDED`...). Isso foi uma decisão deliberada, não incidental:

- **Auditabilidade total**: dá para reconstruir exatamente como o placar chegou a 2×1 e em que minuto cada evento ocorreu.
- **Replay**: se uma regra de negócio mudar (ex.: como um gol contra deve ser contabilizado) ou um read model precisar ser reconstruído do zero, basta reprocessar o event store — nunca precisamos migrar um "placar" armazenado.
- **Fonte única da verdade**: `EventoPartida` no PostgreSQL é a tabela append-only com `sequence` incremental por partida (constraint única `match_code + sequence`). Os dois read models (estatísticas e placar ao vivo) são só projeções descartáveis dessa mesma fonte.

Exemplo de consulta no event store (a mesma ideia por trás do design original):

```sql
SELECT * FROM eventos_partida
WHERE match_code = 'ENG-GAN-13-07-2026'
ORDER BY sequence;
```

### Por que Kafka particionado por partida

O tópico `partidas.eventos` tem 3 partições e cada mensagem é publicada com a **match code** como chave. O Kafka decide a partição com `partition = hash(key) % num_partitions` — logo, **todo evento da mesma partida cai sempre na mesma partição**, na ordem em que foi produzido. Isso é essencial para o event sourcing: sem essa garantia de ordem, `GOAL` poderia ser processado antes de `MATCH_STARTED` em algum consumidor.

Isso também dá paralelismo real: eventos de partidas diferentes podem ser processados por consumidores diferentes do mesmo grupo, e se um consumidor cai, o Kafka rebalanceia as partições órfãs para os sobreviventes automaticamente — sem intervenção manual e sem perder eventos (os offsets não confirmados são reprocessados pelo novo dono da partição).

### Consistência e garantias de entrega

O requisito não funcional de "consistência forte, sem perda de dados" foi endereçado nestas camadas:

- **Escrita durável antes de qualquer publicação**: o evento é gravado no PostgreSQL dentro de uma transação antes de existir em qualquer outro lugar. Se a transação não commitar, nada é publicado.
- **Outbox-lite (at-least-once garantido)**: a publicação no Kafka só ocorre `AFTER_COMMIT` ([`MatchEventRelay`](src/main/java/com/placarcopa/messaging/MatchEventRelay.java)). Se o broker estiver fora do ar no momento, o evento fica marcado como não publicado (`publicado = false`) e o [`RepublicadorEventosPendentes`](src/main/java/com/placarcopa/messaging/RepublicadorEventosPendentes.java) o reenvia periodicamente — nenhum evento persistido é perdido silenciosamente.
- **Idempotência nas projeções**: como cada consumidor de um grupo é dono exclusivo de suas partições, duplicação por design não deveria ocorrer — mas entrega *at-least-once* pode reentregar mensagens após um rebalanceamento. Por isso `PlacarAoVivo` e `EstatisticaPartida` descartam qualquer mensagem cuja `sequence` já foi aplicada, tornando o reprocessamento seguro.
- **Dead-letter topic**: mensagens que falham repetidamente no consumo (`partidas.eventos.dlt`) não são descartadas — ficam disponíveis para reprocessamento manual.
- **Trade-off explícito**: o event store em si tem consistência forte (transacional, PostgreSQL). Os read models (placar e estatísticas) são **eventualmente consistentes** em relação a ele — podem ficar alguns milissegundos atrás durante um pico, mas nunca divergem de forma permanente nem perdem eventos, graças à combinação replay + idempotência acima.

---

## Modelo de eventos

Cada linha do event store vira, no Kafka, uma mensagem no formato:

```json
{
  "id": "12010",
  "match": "ENG-GAN-13-07-2026",
  "team_A": "England",
  "team_B": "GANA",
  "competition": { "title": "world-cup-2026", "stage": "groups_stage" },
  "event": "MATCH_STARTED",
  "minute": "0",
  "sequence": 1,
  "payload": {}
}
```

Tipos de evento suportados (`TipoEvento`):

| Ciclo de vida da partida | Lance de jogo |
|---|---|
| `MATCH_STARTED` | `GOAL` |
| `HALF_TIME` | `OWN_GOAL` |
| `SECOND_HALF_STARTED` | `PENALTY_GOAL` |
| `EXTRA_TIME_STARTED` | `GOAL_CANCELLED` (anulado pelo VAR) |
| `PENALTIES_STARTED` | `MISSED_PENALTY` |
| `MATCH_ENDED` | `YELLOW_CARD` |
| `MATCH_SUSPENDED` | `RED_CARD` |
| `MATCH_POSTPONED` | `SUBSTITUTION` |
| `MATCH_CANCELLED` | `VAR_DECISION` |

---

## API HTTP

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/placares` | Lista o placar ao vivo de todas as partidas (Redis) |
| `GET` | `/api/placares/{match}` | Placar, cartões e status de uma partida |
| `GET` | `/api/placares/stream` | Server-Sent Events — snapshot inicial + atualizações em tempo real |
| `GET` | `/api/estatisticas` | Lista as estatísticas persistidas de todas as partidas (PostgreSQL) |
| `GET` | `/api/estatisticas/{match}` | Estatísticas completas de uma partida |
| `POST` | `/api/ingestao/ao-vivo?ligas=1,2` | Dispara a ingestão manualmente (o poll automático já roda sozinho) |
| `POST` | `/api/ingestao/ligas/{ligaId}/temporadas/{temporada}` | Ingestão de uma competição/temporada inteira |
| `GET` | `/api/ingestao/status` | Saúde da ingestão: último sucesso, se a cota do provedor está esgotada e quando será a próxima tentativa |

O frontend (`http://localhost:8080/`) é uma página estática que consome `/api/placares` e `/api/placares/stream`, com indicação visual quando um placar está desatualizado (ex.: cota do provedor de dados esgotada).

---

## Estrutura do projeto

```
src/main/java/com/placarcopa/
├── dataprovider/     Cliente HTTP da API-Football (RestClient) + DTOs do fixture cru
├── ingestion/        Poll agendado, tradução fixture → eventos, dedup, reconciliação
├── domain/           Modelo de negócio: EventoPartida (event store), EstatisticaPartida, TipoEvento
├── messaging/        Contrato Kafka (MatchEventMessage), producer, consumers, outbox, DLT
├── stats/            Read models de leitura rápida: PlacarAoVivo (Redis), SSE, pub/sub
└── config/           Configurações auxiliares de Kafka (produtor/admin)
```

---

## Como rodar

### Pré-requisitos

- Java 21
- Maven
- Docker (PostgreSQL, Kafka, Redis e Kafdrop via `docker-compose.yml`)
- Uma chave da [API-Football](https://www.api-football.com/) (o plano Free já é suficiente para desenvolvimento, com cota de 100 requisições/dia)

### Passos

```bash
# 1. Suba a infraestrutura
docker compose up -d

# 2. Configure sua chave
cp .env.example .env
# edite .env e preencha APIFOOTBALL_KEY=

# 3. Rode a aplicação
mvn spring-boot:run
```

A aplicação sobe em `http://localhost:8080`. O Kafdrop (inspeção visual dos tópicos) fica em `http://localhost:19000`.

O poll de ingestão roda automaticamente a cada 60 segundos (`ingestao.agendada.*` em `application.yml`); pode ser desligado com `ingestao.agendada.habilitada=false` e disparado manualmente via `POST /api/ingestao/ao-vivo`.

---

## Testes

```bash
mvn test
```

A suíte usa [Testcontainers](https://testcontainers.com/) para rodar contra PostgreSQL e Kafka reais (não mocks) — **requer Docker em execução**. Cobre, entre outros: parsing do data provider, dedup/reconciliação da ingestão, o fluxo completo evento → Kafka → dois read models, e o backoff exponencial quando a cota do provedor se esgota.

---

## Aderência aos requisitos e próximos passos

Um resumo honesto de onde a implementação atual está em relação à especificação original:

| Requisito | Situação |
|---|---|
| Resultado em tempo real sem refresh | ✅ Implementado via SSE + Redis pub/sub |
| Estatísticas/histórico de partidas | 🟡 Parcial — estatísticas por partida (gols, cartões, substituições, pênaltis perdidos) existem; agregações por time/campeonato/jogador ainda não |
| 10M usuários simultâneos | 🔲 Não realizado — infraestrutura atual é single-node (docker-compose local). A arquitetura (Kafka particionado, CQRS, Redis para leitura quente) foi desenhada para permitir escalar horizontalmente cada camada, mas isso ainda não foi testado/provisionado em escala |
| Alta disponibilidade 24/7 | 🔲 Não realizado — Postgres, Kafka e Redis rodam como instância única; precisariam de réplicas/cluster para HA real |
| Consistência forte, zero perda de dados | ✅ Ver [Consistência e garantias de entrega](#consistência-e-garantias-de-entrega) — event store transacional + outbox + idempotência |
