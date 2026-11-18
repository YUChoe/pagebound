package io.github.yuchoe.pagebound.core.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리 작업 감사 로그 (spec/features/F12-admin.md 12절). detail에는 비밀번호·토큰 값을 넣지 않는다.
 */
@Repository
public class AuditLog {

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** actorId가 null이면 시스템 작업. detail은 문자열 값만 담은 JSON 객체로 저장. */
    public void record(Long actorId, String action, String targetType, Object targetId, Map<String, ?> detail) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_id, action, target_type, target_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(actorId, action, targetType, targetId == null ? null : targetId.toString(), toJson(detail),
                        Instant.now().toString())
                .update();
    }

    /** 보관 기간(일)이 지난 감사 로그 삭제. */
    public int purgeOlderThan(int days) {
        return jdbc.sql("DELETE FROM audit_log WHERE created_at < ?")
                .param(Instant.now().minus(Duration.ofDays(days)).toString()).update();
    }

    static String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        return detail.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + quote(String.valueOf(e.getValue())))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
