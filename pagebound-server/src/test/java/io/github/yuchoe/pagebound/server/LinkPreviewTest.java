package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.sun.net.httpserver.HttpServer;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.server.preview.GitLabUrl;
import io.github.yuchoe.pagebound.server.preview.LinkPreviewProperties;
import io.github.yuchoe.pagebound.server.preview.LinkPreviewService;
import io.github.yuchoe.pagebound.server.preview.SafeFetcher;

/**
 * 링크 미리보기 (spec/features/F10-link-preview.md 10절). GitLab은 JDK HttpServer로 흉내 낸다.
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-preview")
class LinkPreviewTest {

    static final HttpServer GITLAB;
    static final Map<String, String> RESPONSES = new ConcurrentHashMap<>();
    static final AtomicInteger CALLS = new AtomicInteger();

    static {
        try {
            GITLAB = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        GITLAB.createContext("/", exchange -> {
            CALLS.incrementAndGet();
            String key = exchange.getRequestURI().getRawPath();
            String body = "secret".equals(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN")) ? RESPONSES.get(key) : null;
            byte[] bytes = (body == null ? "{\"message\":\"404 Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", key.endsWith("/raw") ? "text/plain" : "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GITLAB.start();
    }

    static String base() {
        return "http://127.0.0.1:" + GITLAB.getAddress().getPort();
    }

    @DynamicPropertySource
    static void gitlab(DynamicPropertyRegistry registry) {
        registry.add("pagebound.link-preview.gitlab[0].name", () -> "gitlab-a");
        registry.add("pagebound.link-preview.gitlab[0].base-url", LinkPreviewTest::base);
        registry.add("pagebound.link-preview.gitlab[0].token", () -> "secret");
        registry.add("pagebound.link-preview.gitlab[1].name", () -> "gitlab-b");
        registry.add("pagebound.link-preview.gitlab[1].base-url", () -> "http://gitlab-b.invalid");
        registry.add("pagebound.link-preview.gitlab[1].token", () -> "");
    }

    @AfterAll
    static void stop() {
        GITLAB.stop(0);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LinkPreviewService previews;
    @Autowired
    LinkPreviewProperties properties;
    @Autowired
    SafeFetcher fetcher;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM link_previews").update();
        RESPONSES.clear();
        String project = "/api/v4/projects/grp%2Fsub%2Fnea-snmp";
        RESPONSES.put(project + "/merge_requests/13", """
                {"title":"SNMP 수정","state":"opened","work_in_progress":true,"author":{"name":"홍길동"},
                 "reviewers":[{"name":"리뷰어"}],"source_branch":"feat","target_branch":"main",
                 "pipeline":{"status":"success"},"merge_status":"can_be_merged","labels":["bug"],
                 "user_notes_count":3,"updated_at":"2026-11-01T00:00:00Z"}""");
        RESPONSES.put(project + "/repository/commits/abc1234def", """
                {"short_id":"abc1234d","title":"커밋 제목","author_name":"작성자","created_at":"2026-10-01T00:00:00Z"}""");
        RESPONSES.put(project + "/repository/files/src%2Fmain.c/raw", "l1\nl2\nl3\nl4\n");
    }

    @Test
    void parsesNewLegacySubgroupAndFragmentForms() {
        var hosts = properties.gitlab();
        String b = base();
        assertThat(GitLabUrl.parse(b + "/grp/sub/nea-snmp/-/merge_requests/13/diffs#note_7", hosts).orElseThrow())
                .satisfies(u -> {
                    assertThat(u.kind()).isEqualTo("merge_request");
                    assertThat(u.label()).isEqualTo("nea-snmp!13");
                    assertThat(u.canonical()).isEqualTo(b + "/grp/sub/nea-snmp/-/merge_requests/13#note_7");
                });
        assertThat(GitLabUrl.parse(b + "/grp/proj/commit/0a1b2c3d4e", hosts).orElseThrow().kind()).isEqualTo("commit");
        assertThat(GitLabUrl.parse(b + "/grp/proj/-/issues/5", hosts).orElseThrow().label()).isEqualTo("proj#5");
        assertThat(GitLabUrl.parse(b + "/grp/proj/", hosts).orElseThrow().kind()).isEqualTo("project");
        assertThat(GitLabUrl.parse(b + "/grp/proj/-/wikis/설치/가이드", hosts).orElseThrow().ref()).isEqualTo("설치/가이드");
        var blob = GitLabUrl.parse(b + "/grp/proj/-/blob/main/src/a.c#L10-20", hosts).orElseThrow();
        assertThat(List.of(blob.ref(), blob.filePath(), blob.lineStart(), blob.lineEnd()))
                .containsExactly("main", "src/a.c", 10, 20);
        assertThat(GitLabUrl.parse(b + "/grp/proj/-/pipelines/3", hosts)).isEmpty();
        assertThat(GitLabUrl.parse(b + "/users/sign_in", hosts).map(GitLabUrl::kind)).contains("project");
        assertThat(GitLabUrl.parse("http://other/grp/proj", hosts)).isEmpty();
    }

    @Test
    void fetchesCachesAndMapsLegacyFields() {
        String mr = base() + "/grp/sub/nea-snmp/-/merge_requests/13";
        var first = previews.previews(List.of(mr, mr + "/diffs")).getFirst();
        assertThat(first.status()).isEqualTo("ok");
        assertThat(first.data()).containsEntry("state", "draft").containsEntry("pipeline", "success")
                .containsEntry("mergeStatus", "can_be_merged").containsEntry("label", "nea-snmp!13");
        int calls = CALLS.get();
        assertThat(previews.previews(List.of(mr + "/diffs")).getFirst().data()).containsEntry("title", "SNMP 수정");
        assertThat(CALLS.get()).as("캐시 적중").isEqualTo(calls);
        assertThat(jdbc.sql("SELECT count(*) FROM link_previews").query(Integer.class).single()).isEqualTo(1);

        var legacy = previews.previews(List.of(base() + "/grp/sub/nea-snmp/commit/abc1234def")).getFirst();
        assertThat(legacy.data()).containsEntry("title", "커밋 제목");

        var snippet = previews.previews(List.of(base() + "/grp/sub/nea-snmp/-/blob/abc1234/src/main.c#L2-3")).getFirst();
        assertThat(snippet.data()).containsEntry("snippet", "l2\nl3").containsEntry("startLine", 2);
    }

    @Test
    void missingMrIsNotFoundAndStaleCacheIsServedWhileRefreshing() throws Exception {
        String gone = base() + "/grp/sub/nea-snmp/-/merge_requests/999";
        var missing = previews.previews(List.of(gone)).getFirst();
        assertThat(missing.status()).isEqualTo("not_found");
        assertThat(missing.data()).containsEntry("label", "nea-snmp!999");
        String expires = jdbc.sql("SELECT expires_at FROM link_previews").query(String.class).single();
        assertThat(java.time.Instant.parse(expires)).isBefore(java.time.Instant.now().plusSeconds(301));

        // 만료된 캐시: 기존 값 즉시 반환 + 백그라운드 갱신
        String mr = base() + "/grp/sub/nea-snmp/-/merge_requests/13";
        previews.previews(List.of(mr));
        jdbc.sql("UPDATE link_previews SET data = replace(data, 'SNMP 수정', '옛 제목'), expires_at = '2000-01-01T00:00:00Z'")
                .update();
        assertThat(previews.previews(List.of(mr)).getFirst().data()).containsEntry("title", "옛 제목");
        for (int i = 0; i < 50 && previews.previews(List.of(mr)).getFirst().data().get("title").equals("옛 제목"); i++) {
            Thread.sleep(50);
        }
        assertThat(previews.previews(List.of(mr)).getFirst().data()).containsEntry("title", "SNMP 수정");
    }

    @Test
    void hostWithoutTokenAndUnknownSchemesAreUnsupported() {
        var results = previews.previews(List.of("http://gitlab-b.invalid/grp/proj/-/issues/1", "ftp://x/y", "mailto:a@b"));
        assertThat(results).extracting(LinkPreviewService.Preview::status).containsOnly("unsupported");
    }

    @Test
    void ssrfBlocksLoopbackAndMetadataAddresses() {
        for (String url : List.of("http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/", "http://10.0.0.1/",
                "http://[::1]/", "file:///etc/passwd")) {
            assertThatThrownBy(() -> fetcher.get(url, 1024, true, null)).isInstanceOf(WikiException.class)
                    .extracting("code").isIn("BLOCKED_HOST", "BAD_URL");
        }
        assertThat(previews.previews(List.of("http://127.0.0.1:8080/")).getFirst().status()).isEqualTo("forbidden");
    }

    @Test
    void apiRequiresLogin() throws Exception {
        String body = "{\"urls\":[\"" + base() + "/grp/sub/nea-snmp/-/merge_requests/13\"]}";
        mvc.perform(post("/api/v1/link-previews").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/link-previews").with(csrf()).with(user("v@b.c").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("merge_request"))
                .andExpect(jsonPath("$[0].data.title").value("SNMP 수정"));
    }
}
