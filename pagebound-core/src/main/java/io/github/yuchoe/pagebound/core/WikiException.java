package io.github.yuchoe.pagebound.core;

import java.util.Map;

/**
 * 서비스 계층 오류. code는 기계 판별용(VERSION_CONFLICT 등), status는 HTTP 상태 코드.
 */
public class WikiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public WikiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public WikiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public static WikiException notFound(String what) {
        return new WikiException(404, "NOT_FOUND", what + " not found");
    }

    public static WikiException badRequest(String message) {
        return new WikiException(400, "BAD_REQUEST", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
