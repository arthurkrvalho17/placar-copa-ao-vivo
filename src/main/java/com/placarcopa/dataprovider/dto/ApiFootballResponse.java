package com.placarcopa.dataprovider.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Envelope padrão de toda resposta da API-Football.
 * O campo {@code errors} vem como array vazio quando não há erros
 * e como objeto {campo: mensagem} quando há — por isso é um JsonNode.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiFootballResponse<T>(
        String get,
        JsonNode errors,
        Integer results,
        Paging paging,
        List<T> response
) {

    public boolean temErros() {
        return errors != null && errors.isObject() && !errors.isEmpty();
    }

    public String descricaoErros() {
        return temErros() ? errors.toString() : "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paging(Integer current, Integer total) {
    }
}
