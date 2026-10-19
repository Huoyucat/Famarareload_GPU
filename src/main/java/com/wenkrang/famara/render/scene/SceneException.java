package com.wenkrang.famara.render.scene;

public final class SceneException extends RuntimeException {
    public enum Code {SCENE_INCOMPLETE, GEOMETRY_LIMIT, TRANSPARENCY_LIMIT, CANCELLED}

    private final Code code;

    public SceneException(Code code, String detail) {
        super(detail);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
