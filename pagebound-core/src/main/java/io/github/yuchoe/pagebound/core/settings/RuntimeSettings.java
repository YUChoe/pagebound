package io.github.yuchoe.pagebound.core.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.audit.AuditLog;

/**
 * 런타임 설정 (spec/features/F12-admin.md 10절). DB settings 값이 있으면 그것, 없으면 application.yml 기본값.
 * 변경은 즉시 반영된다 (메모리 캐시 갱신).
 */
@Component
public class RuntimeSettings {

    public record Item(String key, String value, String defaultValue, boolean overridden) {
    }

    /** 서버 multipart 한도 (application.yml spring.servlet.multipart.max-file-size와 맞춘다) */
    static final long MULTIPART_LIMIT = DataSize.ofMegabytes(52).toBytes();

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Map<String, String> defaults = new LinkedHashMap<>();
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    public RuntimeSettings(JdbcClient jdbc, AuditLog audit,
                           @Value("${pagebound.site.name:Pagebound}") String siteName,
                           @Value("${pagebound.site.base-url:}") String baseUrl,
                           @Value("${pagebound.site.home-path:home}") String homePath,
                           @Value("${pagebound.anonymous-read:true}") boolean anonymousRead,
                           @Value("${pagebound.upload.max-file-size:50MB}") DataSize maxFileSize,
                           @Value("${pagebound.trash.retention-days:30}") int trashRetentionDays,
                           @Value("${pagebound.draft.retention-hours:24}") int draftRetentionHours,
                           @Value("${pagebound.mcp.rate-limit-per-minute:60}") int mcpRateLimit) {
        this.jdbc = jdbc;
        this.audit = audit;
        defaults.put("site.name", siteName);
        defaults.put("site.base-url", baseUrl);
        defaults.put("site.home-path", homePath);
        defaults.put("security.anonymous-read", Boolean.toString(anonymousRead));
        defaults.put("upload.max-file-size", maxFileSize.toMegabytes() + "MB");
        defaults.put("trash.retention-days", Integer.toString(trashRetentionDays));
        defaults.put("draft.retention-hours", Integer.toString(draftRetentionHours));
        defaults.put("mcp.rate-limit-per-minute", Integer.toString(mcpRateLimit));
        reload();
    }

    /** 테스트·도구용: application 기본값만으로 생성. */
    public static RuntimeSettings defaults(JdbcClient jdbc, AuditLog audit) {
        return new RuntimeSettings(jdbc, audit, "Pagebound", "", "home", true, DataSize.ofMegabytes(50), 30, 24, 60);
    }

    public void reload() {
        overrides.clear();
        jdbc.sql("SELECT key, value FROM settings").query(rs -> {
            overrides.put(rs.getString(1), rs.getString(2));
        });
    }

    public List<Item> items() {
        return defaults.entrySet().stream()
                .map(e -> new Item(e.getKey(), get(e.getKey()), e.getValue(), overrides.containsKey(e.getKey())))
                .toList();
    }

    /** 값 검증 후 저장. 빈 문자열(null)이면 기본값으로 되돌린다. */
    @Transactional
    public void update(Map<String, String> changes, long actorId) {
        changes.forEach((key, value) -> {
            if (!defaults.containsKey(key)) {
                throw WikiException.badRequest("unknown setting: " + key);
            }
            if (value != null && !value.isBlank()) {
                validate(key, value.strip());
            }
        });
        String now = Instant.now().toString();
        changes.forEach((key, value) -> {
            String before = get(key);
            if (value == null || value.isBlank()) {
                jdbc.sql("DELETE FROM settings WHERE key = ?").param(key).update();
            } else {
                jdbc.sql("INSERT OR REPLACE INTO settings (key, value, updated_by, updated_at) VALUES (?, ?, ?, ?)")
                        .params(key, value.strip(), actorId, now).update();
            }
            audit.record(actorId, "SETTING_UPDATE", "setting", key,
                    Map.of("from", before, "to", value == null || value.isBlank() ? defaults.get(key) : value.strip()));
        });
        reload();
    }

    private static void validate(String key, String value) {
        switch (key) {
            case "security.anonymous-read" -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw WikiException.badRequest(key + " must be true or false");
                }
            }
            case "upload.max-file-size" -> {
                long bytes;
                try {
                    bytes = DataSize.parse(value).toBytes();
                } catch (IllegalArgumentException e) {
                    throw WikiException.badRequest(key + " must be a size like 50MB");
                }
                if (bytes <= 0 || bytes > MULTIPART_LIMIT) {
                    throw WikiException.badRequest(key + " must be within 52MB");
                }
            }
            case "trash.retention-days", "draft.retention-hours", "mcp.rate-limit-per-minute" -> {
                try {
                    if (Integer.parseInt(value) < 1) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw WikiException.badRequest(key + " must be a positive integer");
                }
            }
            case "site.base-url" -> {
                if (!value.matches("https?://\\S+")) {
                    throw WikiException.badRequest(key + " must start with http:// or https://");
                }
            }
            default -> {
                // site.name, site.home-path: 자유 텍스트
            }
        }
    }

    public String get(String key) {
        return overrides.getOrDefault(key, defaults.get(key));
    }

    public String siteName() {
        return get("site.name");
    }

    public String baseUrl() {
        return get("site.base-url").replaceAll("/+$", "");
    }

    public String homePath() {
        return get("site.home-path");
    }

    public boolean anonymousRead() {
        return Boolean.parseBoolean(get("security.anonymous-read"));
    }

    public long maxFileSizeBytes() {
        return DataSize.parse(get("upload.max-file-size")).toBytes();
    }

    public int trashRetentionDays() {
        return Integer.parseInt(get("trash.retention-days"));
    }

    public int draftRetentionHours() {
        return Integer.parseInt(get("draft.retention-hours"));
    }

    public int mcpRateLimitPerMinute() {
        return Integer.parseInt(get("mcp.rate-limit-per-minute"));
    }
}
