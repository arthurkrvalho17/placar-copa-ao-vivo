package com.placarcopa.dataprovider;

public class ApiFootballException extends RuntimeException {

    public ApiFootballException(String message) {
        super(message);
    }

    public ApiFootballException(String message, Throwable cause) {
        super(message, cause);
    }
}
