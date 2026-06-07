package com.mnemoscape.common.exception;

public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }

    public static BizException notFound(String resource, String id) {
        return new BizException(404, resource + " with id " + id + " not found");
    }

    public static BizException unauthorized() {
        return new BizException(401, "Authentication required");
    }

    public static BizException forbidden() {
        return new BizException(403, "Access denied");
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message);
    }

    public static BizException internalError(String message) {
        return new BizException(500, message);
    }
}
