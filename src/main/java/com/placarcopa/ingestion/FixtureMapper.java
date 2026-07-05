package com.placarcopa.ingestion;

import com.placarcopa.dataprovider.dto.FixtureResponse;
import com.placarcopa.dataprovider.dto.MatchEvent;
import com.placarcopa.domain.TipoEvento;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Camada anticorrupção: transforma um fixture cru da API-Football na lista
 * de eventos do nosso modelo, na ordem cronológica da partida.
 *
 * Eventos de ciclo de vida (MATCH_STARTED, HALF_TIME...) são derivados do
 * status atual; os demais (gols, cartões...) vêm da linha do tempo da API.
 */
@Component
public class FixtureMapper {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /** Evento extraído do fixture, ainda sem sequence — o service atribui ao persistir. */
    public record EventoProposto(TipoEvento event, Integer minute, Map<String, Object> payload) {

        /** Identidade do evento dentro da partida, usada para não reinserir nos próximos polls. */
        public String chaveDeduplicacao() {
            return chave(event, minute, payload);
        }

        static String chave(TipoEvento event, Integer minute, Map<String, Object> payload) {
            if (event.isCicloDeVida()) {
                return event.name();
            }
            Object player = payload == null ? null : payload.get("player");
            Object detail = payload == null ? null : payload.get("detail");
            return event.name() + "|" + minute + "|" + player + "|" + detail;
        }
    }

    /** Ex.: ENG-GAN-13-07-2026 (código dos times + data da partida). */
    public String matchCode(FixtureResponse fixture) {
        String data = fixture.fixture().date() == null
                ? String.valueOf(fixture.fixture().id())
                : FORMATO_DATA.format(fixture.fixture().date());
        return codigoTime(fixture.teams().home().name()) + "-"
                + codigoTime(fixture.teams().away().name()) + "-" + data;
    }

    /** Ex.: world-cup-2026. */
    public String competitionTitle(FixtureResponse fixture) {
        FixtureResponse.League liga = fixture.league();
        if (liga == null || liga.name() == null) {
            return null;
        }
        String slug = slug(liga.name());
        return liga.season() == null ? slug : slug + "-" + liga.season();
    }

    /** Ex.: groups_stage, round_of_16, quarter_finals, semi_finals, final. */
    public String competitionStage(FixtureResponse fixture) {
        FixtureResponse.League liga = fixture.league();
        if (liga == null || liga.round() == null) {
            return null;
        }
        String round = liga.round().toLowerCase(Locale.ROOT);
        if (round.contains("group")) {
            return "groups_stage";
        }
        if (round.contains("round of 16") || round.contains("16th")) {
            return "round_of_16";
        }
        if (round.contains("quarter")) {
            return "quarter_finals";
        }
        if (round.contains("semi")) {
            return "semi_finals";
        }
        if (round.contains("3rd place") || round.contains("third place")) {
            return "third_place";
        }
        if (round.contains("final")) {
            return "final";
        }
        return slug(round).replace('-', '_');
    }

    /** Todos os eventos que o estado atual do fixture implica, em ordem cronológica. */
    public List<EventoProposto> extrairEventos(FixtureResponse fixture) {
        List<EventoProposto> eventos = new ArrayList<>(eventosDeCicloDeVida(fixture));
        eventos.addAll(eventosDaLinhaDoTempo(fixture));
        eventos.sort(Comparator.comparing(e -> e.minute() == null ? Integer.MAX_VALUE : e.minute()));
        return eventos;
    }

    private List<EventoProposto> eventosDeCicloDeVida(FixtureResponse fixture) {
        FixtureResponse.Status status = fixture.fixture().status();
        String codigo = status == null || status.codigo() == null ? "" : status.codigo().toUpperCase();
        Integer minutoAtual = status == null ? null : status.elapsed();

        List<EventoProposto> eventos = new ArrayList<>();
        switch (codigo) {
            case "1H", "LIVE" -> eventos.add(cicloDeVida(TipoEvento.MATCH_STARTED, 0));
            case "HT" -> {
                eventos.add(cicloDeVida(TipoEvento.MATCH_STARTED, 0));
                eventos.add(cicloDeVida(TipoEvento.HALF_TIME, 45));
            }
            case "2H" -> {
                eventos.add(cicloDeVida(TipoEvento.MATCH_STARTED, 0));
                eventos.add(cicloDeVida(TipoEvento.HALF_TIME, 45));
                eventos.add(cicloDeVida(TipoEvento.SECOND_HALF_STARTED, 46));
            }
            case "ET", "BT" -> {
                adicionarTempoNormal(eventos);
                eventos.add(cicloDeVida(TipoEvento.EXTRA_TIME_STARTED, 90));
            }
            case "P" -> {
                adicionarTempoNormal(eventos);
                eventos.add(cicloDeVida(TipoEvento.EXTRA_TIME_STARTED, 90));
                eventos.add(cicloDeVida(TipoEvento.PENALTIES_STARTED, 120));
            }
            case "FT" -> {
                adicionarTempoNormal(eventos);
                eventos.add(cicloDeVida(TipoEvento.MATCH_ENDED, minutoOuPadrao(minutoAtual, 90)));
            }
            case "AET" -> {
                adicionarTempoNormal(eventos);
                eventos.add(cicloDeVida(TipoEvento.EXTRA_TIME_STARTED, 90));
                eventos.add(cicloDeVida(TipoEvento.MATCH_ENDED, minutoOuPadrao(minutoAtual, 120)));
            }
            case "PEN" -> {
                adicionarTempoNormal(eventos);
                eventos.add(cicloDeVida(TipoEvento.EXTRA_TIME_STARTED, 90));
                eventos.add(cicloDeVida(TipoEvento.PENALTIES_STARTED, 120));
                eventos.add(cicloDeVida(TipoEvento.MATCH_ENDED, minutoOuPadrao(minutoAtual, 120)));
            }
            case "SUSP", "INT" -> {
                eventos.add(cicloDeVida(TipoEvento.MATCH_STARTED, 0));
                eventos.add(cicloDeVida(TipoEvento.MATCH_SUSPENDED, minutoOuPadrao(minutoAtual, 0)));
            }
            case "PST" -> eventos.add(cicloDeVida(TipoEvento.MATCH_POSTPONED, 0));
            case "CANC", "ABD", "AWD", "WO" -> eventos.add(cicloDeVida(TipoEvento.MATCH_CANCELLED, 0));
            default -> {
                // NS, TBD ou desconhecido: partida ainda não gera eventos
            }
        }
        return eventos;
    }

    private void adicionarTempoNormal(List<EventoProposto> eventos) {
        eventos.add(cicloDeVida(TipoEvento.MATCH_STARTED, 0));
        eventos.add(cicloDeVida(TipoEvento.HALF_TIME, 45));
        eventos.add(cicloDeVida(TipoEvento.SECOND_HALF_STARTED, 46));
    }

    private List<EventoProposto> eventosDaLinhaDoTempo(FixtureResponse fixture) {
        if (fixture.events() == null) {
            return List.of();
        }
        List<EventoProposto> eventos = new ArrayList<>();
        for (MatchEvent evento : fixture.events()) {
            TipoEvento tipo = TipoEvento.deEventoApi(evento.type(), evento.detail());
            if (tipo == null) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (evento.team() != null && evento.team().name() != null) {
                payload.put("team", evento.team().name());
            }
            if (evento.player() != null && evento.player().name() != null) {
                payload.put("player", evento.player().name());
            }
            if (evento.assist() != null && evento.assist().name() != null) {
                payload.put("related_player", evento.assist().name());
            }
            if (evento.detail() != null) {
                payload.put("detail", evento.detail());
            }
            Integer minuto = evento.time() == null ? null : evento.time().elapsed();
            eventos.add(new EventoProposto(tipo, minuto, payload));
        }
        return eventos;
    }

    private EventoProposto cicloDeVida(TipoEvento tipo, Integer minuto) {
        return new EventoProposto(tipo, minuto, new LinkedHashMap<>());
    }

    private Integer minutoOuPadrao(Integer minuto, int padrao) {
        return minuto == null ? padrao : minuto;
    }

    private String codigoTime(String nome) {
        String letras = semAcentos(nome).replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return letras.length() <= 3 ? letras : letras.substring(0, 3);
    }

    private String slug(String texto) {
        String limpo = semAcentos(texto).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return limpo.replaceAll("(^-|-$)", "");
    }

    private String semAcentos(String texto) {
        return Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
