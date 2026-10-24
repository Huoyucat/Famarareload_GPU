package com.wenkrang.famara.render.gpu;

import java.util.Objects;

/** Structured backend failures; photo/film transaction handling stays with the caller. */
public final class BackendException extends RuntimeException {
    public enum Code { UNAVAILABLE, UNSUPPORTED, OUT_OF_MEMORY, CANCELLED, TIMEOUT, DEVICE_ERROR, CLOSED }
    private final Code code;
    public BackendException(Code code,String message){super(message);this.code=Objects.requireNonNull(code);}
    public BackendException(Code code,String message,Throwable cause){super(message,cause);this.code=Objects.requireNonNull(code);}
    public Code code(){return code;}
}
