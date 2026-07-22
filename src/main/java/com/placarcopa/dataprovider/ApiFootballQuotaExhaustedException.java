package com.placarcopa.dataprovider;

/**
 * A cota diária (ou por minuto) do plano contratado foi atingida.
 * Sinaliza ao chamador que insistir imediatamente é inútil — deve esperar.
 */
public class ApiFootballQuotaExhaustedException extends ApiFootballException {

    public ApiFootballQuotaExhaustedException(String message) {
        super(message);
    }
}
