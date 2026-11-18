package io.github.yuchoe.pagebound.server.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.yuchoe.pagebound.core.Actor;
import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.server.security.TokenAuthentication;

/**
 * MCP 도구 정의. handler가 돌려준 객체는 JSON 텍스트로, CallToolResult는 그대로 응답한다.
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema, boolean write,
                      Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Object handle(Args args, Caller caller) throws Exception;
    }

    /** 호출 주체. baseUrl은 영구 링크·첨부 절대 URL용. */
    public record Caller(TokenAuthentication auth, String baseUrl) {

        public Actor actor() {
            return new Actor(auth.token().userId(), "mcp", auth.token().tokenId());
        }

        public long userId() {
            return auth.token().userId();
        }

        public String permalink(long pageId) {
            return baseUrl + "/p/" + pageId;
        }
    }

    /** 도구 인자 접근. 형식이 맞지 않으면 400 BAD_REQUEST. */
    public record Args(Map<String, Object> raw) {

        public Args {
            raw = raw == null ? Map.of() : raw;
        }

        public boolean has(String name) {
            return raw.get(name) != null;
        }

        public String str(String name) {
            String value = optStr(name);
            if (value == null || value.isEmpty()) {
                throw WikiException.badRequest(name + " is required");
            }
            return value;
        }

        public String optStr(String name) {
            Object value = raw.get(name);
            if (value == null) {
                return null;
            }
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof Number n) {
                return n.toString();
            }
            throw WikiException.badRequest(name + " must be a string");
        }

        public int integer(String name, int defaultValue, int min, int max) {
            Object value = raw.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                int n = value instanceof Number num ? num.intValue() : Integer.parseInt(value.toString().strip());
                return Math.clamp(n, min, max);
            } catch (NumberFormatException e) {
                throw WikiException.badRequest(name + " must be an integer");
            }
        }

        public int requiredInt(String name) {
            if (!has(name)) {
                throw WikiException.badRequest(name + " is required");
            }
            return integer(name, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        public boolean bool(String name) {
            Object value = raw.get(name);
            return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(value.toString());
        }

        public List<String> strList(String name) {
            Object value = raw.get(name);
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> list)) {
                throw WikiException.badRequest(name + " must be an array of strings");
            }
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw WikiException.badRequest(name + " must be an array of strings");
                }
                out.add(s);
            }
            return out;
        }

        public List<Map<String, Object>> objectList(String name) {
            Object value = raw.get(name);
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> list)) {
                throw WikiException.badRequest(name + " must be an array of objects");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw WikiException.badRequest(name + " must be an array of objects");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((k, v) -> entry.put(String.valueOf(k), v));
                out.add(entry);
            }
            return out;
        }
    }

    /** JSON Schema(object) 작성 도우미. */
    public static final class Schema {

        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public static Schema object() {
            return new Schema();
        }

        public Schema str(String name, String description, boolean isRequired) {
            return prop(name, Map.of("type", "string", "description", description), isRequired);
        }

        public Schema integer(String name, String description, boolean isRequired) {
            return prop(name, Map.of("type", "integer", "description", description), isRequired);
        }

        public Schema bool(String name, String description) {
            return prop(name, Map.of("type", "boolean", "description", description), false);
        }

        public Schema strArray(String name, String description) {
            return prop(name, Map.of("type", "array", "items", Map.of("type", "string"), "description", description),
                    false);
        }

        public Schema prop(String name, Map<String, Object> schema, boolean isRequired) {
            properties.put(name, schema);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", required);
            return schema;
        }
    }
}
