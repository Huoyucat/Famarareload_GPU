package com.wenkrang.famara.render.asset;

public final class AssetException extends RuntimeException {
    public enum Code {MISSING, INVALID, UNSUPPORTED, LIMIT, SOURCE_CHANGED, GENERATION_MISMATCH}

    private final Code code;
    private final String resource;

    public AssetException(Code code, String resource, String message) {
        super(message + "：" + resource);
        this.code = code;
        this.resource = resource;
    }

    public AssetException(Code code, String resource, String message, Throwable cause) {
        this(code, resource, message);
        initCause(cause);
    }

    public Code code() {
        return code;
    }

    public String resource() {
        return resource;
    }
}
