package io.github.yuchoe.pagebound.server.mcp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * MCP 호출 로그 (spec/features/F12-admin.md 9절): 토큰·도구·대상 경로·결과 코드·시각. 90일 보관.
 */
@Component
public class McpCallLog {

    public record Call(long id, Long tokenId, String tokenName, String user, String tool, String target, String result,
                       String createdAt) {
    }

    public record DailyCount(String day, Long tokenId, String tokenName, int calls) {
    }

    public record CallPage(int total, int page, List<Call> items, List<DailyCount> daily) {
    }

    static final int RETENTION_DAYS = 90;

    private final JdbcClient jdbc;

    public McpCallLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long tokenId, String tool, String target, String result) {
        jdbc.sql("INSERT INTO mcp_call_log (token_id, tool, target, result, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(tokenId, tool, target, result, Instant.now().toString()).update();
    }

    /** 필터: 토큰, 도구, 결과(OK가 아닌 것만 보려면 result=ERROR). page는 1부터, 50건 단위. 일간 수는 최근 14일. */
    public CallPage list(Long tokenId, String tool, String result, int page) {
        String where = """
                WHERE (:token IS NULL OR c.token_id = :token) AND (:tool IS NULL OR c.tool = :tool)
                  AND (:result IS NULL OR (:result = 'ERROR' AND c.result <> 'OK') OR c.result = :result)""";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("token", tokenId);
        params.put("tool", tool == null || tool.isBlank() ? null : tool);
        params.put("result", result == null || result.isBlank() ? null : result);
        int total = jdbc.sql("SELECT count(*) FROM mcp_call_log c " + where).params(params).query(Integer.class).single();
        params.put("offset", (Math.max(page, 1) - 1) * 50);
        List<Call> items = jdbc.sql("""
                SELECT c.id, c.token_id, t.name, u.name, c.tool, c.target, c.result, c.created_at
                FROM mcp_call_log c LEFT JOIN api_tokens t ON t.id = c.token_id LEFT JOIN users u ON u.id = t.user_id
                %s ORDER BY c.id DESC LIMIT 50 OFFSET :offset""".formatted(where))
                .params(params)
                .query((rs, n) -> new Call(rs.getLong(1), (Long) rs.getObject(2, Long.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .list();
        List<DailyCount> daily = jdbc.sql("""
                SELECT substr(c.created_at, 1, 10) AS day, c.token_id, t.name, count(*)
                FROM mcp_call_log c LEFT JOIN api_tokens t ON t.id = c.token_id
                WHERE c.created_at >= ? GROUP BY day, c.token_id ORDER BY day DESC, count(*) DESC""")
                .param(Instant.now().minus(14, ChronoUnit.DAYS).toString())
                .query((rs, n) -> new DailyCount(rs.getString(1), (Long) rs.getObject(2, Long.class), rs.getString(3),
                        rs.getInt(4)))
                .list();
        return new CallPage(total, Math.max(page, 1), items, daily);
    }

    public int purgeExpired() {
        return jdbc.sql("DELETE FROM mcp_call_log WHERE created_at < ?")
                .param(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS).toString()).update();
    }
}
