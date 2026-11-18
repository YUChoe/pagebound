package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.token.ApiTokenService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP Streamable HTTP 종단 테스트 (spec/features/F6-mcp-api.md 6절). MCP 서블릿은 MockMvc로 닿지 않아 실제 포트를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pagebound.data-dir=target/it-mcp")
class McpApiTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LinkIndexer links;
    @Autowired
    AttachmentStorage storage;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ApiTokenService tokens;
    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        TestData.seed(jdbc, links, storage, encoder);
    }

    /** initialize → initialized 후 세션 id를 들고 다니는 최소 클라이언트. */
    private final class Session {
        final String token;
        String sessionId;

        Session(String token) throws Exception {
            this.token = token;
            HttpResponse<String> init = post(rpc("initialize", Map.of("protocolVersion", "2025-06-18",
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))));
            assertThat(init.statusCode()).isEqualTo(200);
            sessionId = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            post(json.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
        }

        JsonNode request(String method, Object params) throws Exception {
            HttpResponse<String> response = post(rpc(method, params));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return parse(response.body());
        }

        /** tools/call 결과의 첫 텍스트를 JSON으로. */
        JsonNode call(String tool, Map<String, Object> args) throws Exception {
            return json.readTree(callRaw(tool, args).get("content").get(0).get("text").asString());
        }

        JsonNode callRaw(String tool, Map<String, Object> args) throws Exception {
            return request("tools/call", Map.of("name", tool, "arguments", args)).get("result");
        }

        HttpResponse<String> post(String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (sessionId != null) {
                b.header("Mcp-Session-Id", sessionId);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String rpc(String method, Object params) {
        return json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(), "method", method,
                "params", params));
    }

    /** 응답이 SSE면 data 줄을 꺼낸다. */
    private JsonNode parse(String body) {
        String trimmed = body.strip();
        if (!trimmed.startsWith("{")) {
            trimmed = trimmed.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).strip())
                    .reduce((a, b) -> b).orElseThrow();
        }
        return json.readTree(trimmed);
    }

    private HttpResponse<String> anonymous(String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(rpc("initialize", Map.of())));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<String> toolNames(Session s) throws Exception {
        return s.request("tools/list", Map.of()).get("result").get("tools").valueStream()
                .map(t -> t.get("name").asString()).toList();
    }

    @Test
    void unauthenticatedInvalidAndRevokedTokensAre401() throws Exception {
        assertThat(anonymous(null).statusCode()).isEqualTo(401);
        assertThat(anonymous("Bearer pb_wrong").statusCode()).isEqualTo(401);
        var issued = tokens.issue(1, "폐기될 토큰", "read", 30);
        tokens.revoke(issued.id(), 1L, 1);
        assertThat(anonymous("Bearer " + issued.token()).statusCode()).isEqualTo(401);
    }

    @Test
    void readTokenSeesNoWriteToolsAndCannotCallThem() throws Exception {
        Session s = new Session(tokens.issue(1, "읽기", "read", null).token());
        assertThat(toolNames(s)).hasSize(10).contains("search_pages", "get_page", "get_attachment")
                .doesNotContain("update_page", "create_page", "trash_page");
        JsonNode denied = s.callRaw("trash_page", Map.of("path", "home"));
        assertThat(denied.get("isError").asBoolean()).isTrue();
        assertThat(denied.get("content").get(0).get("text").asString()).contains("FORBIDDEN");
    }

    @Test
    void agentScenarioSearchOutlineSectionUpdateUpload() throws Exception {
        var issued = tokens.issue(1, "claude", "write", 90);
        Session s = new Session(issued.token());
        assertThat(toolNames(s)).hasSize(16);

        JsonNode hits = s.call("search_pages", Map.of("query", "본문"));
        assertThat(hits.get("items").get(0).get("path").asString()).isEqualTo("nems/통계");
        assertThat(hits.get("items").get(0).get("permalink").asString()).endsWith("/p/2");

        JsonNode outline = s.call("get_outline", Map.of("path", "nems/통계"));
        assertThat(outline.get("sections").get(1).get("number").asString()).isEqualTo("1.1.");

        JsonNode section = s.call("get_page", Map.of("path", "nems/통계", "section", "1.1"));
        assertThat(section.get("content").asString()).isEqualTo("## 설정\n본문\n");
        int version = section.get("version").asInt();

        JsonNode conflict = s.call("update_page", Map.of("path", "nems/통계", "base_version", version - 1,
                "section", "설정", "content", "## 설정\nx\n"));
        assertThat(conflict.get("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(conflict.get("currentVersion").asInt()).isEqualTo(version);

        JsonNode dry = s.call("update_page", Map.of("path", "nems/통계", "base_version", version,
                "section", "1.1", "content", "## 설정\n바뀐 본문\n", "dry_run", true));
        assertThat(dry.get("diff").asString()).contains("-본문").contains("+바뀐 본문");

        JsonNode updated = s.call("update_page", Map.of("path", "nems/통계", "base_version", version,
                "section", "1.1", "content", "## 설정\n바뀐 본문\n", "summary", "에이전트 수정"));
        assertThat(updated.get("version").asInt()).isEqualTo(version + 1);
        assertThat(jdbc.sql("SELECT source || ':' || token_id FROM page_revisions WHERE page_id = 2 AND version = ?")
                .param(version + 1).query(String.class).single()).isEqualTo("mcp:" + issued.id());

        JsonNode notUnique = s.call("update_page", Map.of("path", "nems/통계", "base_version", version + 1,
                "edits", List.of(Map.of("old", "없는 문자열", "new", "x"))));
        assertThat(notUnique.get("code").asString()).isEqualTo("EDIT_NOT_UNIQUE");

        String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        JsonNode uploaded = s.call("upload_attachment", Map.of("path", "nems/통계", "filename", "결과.png",
                "content_base64", png));
        String markdown = uploaded.get("markdown").asString();
        assertThat(markdown).isEqualTo("![결과.png](/p/2/files/%EA%B2%B0%EA%B3%BC.png)");

        JsonNode inserted = s.call("update_page", Map.of("path", "nems/통계", "base_version", version + 1,
                "edits", List.of(Map.of("old", "바뀐 본문", "new", "바뀐 본문\n" + markdown))));
        assertThat(inserted.get("warnings")).isEmpty();

        JsonNode image = s.callRaw("get_attachment", Map.of("path", "nems/통계", "filename", "결과.png"));
        assertThat(image.get("content").get(0).get("type").asString()).isEqualTo("image");
        assertThat(image.get("content").get(0).get("mimeType").asString()).isEqualTo("image/png");

        JsonNode history = s.call("get_history", Map.of("path", "nems/통계", "limit", 1));
        assertThat(history.get(0).get("tokenName").asString()).isEqualTo("claude");
    }

    @Test
    void uploadFromPrivateSourceUrlIsBlocked() throws Exception {
        Session s = new Session(tokens.issue(1, "ssrf", "write", null).token());
        JsonNode blocked = s.call("upload_attachment", Map.of("path", "home", "filename", "meta.txt",
                "source_url", "http://169.254.169.254/latest/meta-data/"));
        assertThat(blocked.get("code").asString()).isEqualTo("BLOCKED_HOST");
        JsonNode both = s.call("upload_attachment", Map.of("path", "home", "filename", "a.txt",
                "source_url", "http://127.0.0.1/", "content_base64", "YQ=="));
        assertThat(both.get("code").asString()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void sixtyFirstCallIsRateLimited() throws Exception {
        var issued = tokens.issue(1, "한도", "read", null);
        // 테스트마다 DB를 비워 토큰 id가 재사용되므로, 메모리 한도 카운터가 겹치지 않게 고유 id로 바꾼다
        jdbc.sql("UPDATE api_tokens SET id = 900000 WHERE id = ?").param(issued.id()).update();
        Session s = new Session(issued.token());
        for (int i = 0; i < 60; i++) {
            assertThat(s.callRaw("list_tags", Map.of()).get("isError").asBoolean()).isFalse();
        }
        JsonNode limited = s.callRaw("list_tags", Map.of());
        assertThat(limited.get("isError").asBoolean()).isTrue();
        assertThat(limited.get("content").get(0).get("text").asString()).contains("RATE_LIMITED");
    }
}
