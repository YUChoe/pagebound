package io.github.yuchoe.pagebound.server;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriUtils;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.server.web.LegacyRedirectFilter;

/**
 * 익명 읽기 허용(기본값) 상태의 조회 API·리다이렉트·첨부·로그인.
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-api")
class PageApiTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LinkIndexer links;
    @Autowired
    AttachmentStorage storage;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    LegacyRedirectFilter legacyRedirect;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(legacyRedirect).apply(springSecurity()).build();
        TestData.seed(jdbc, links, storage, encoder);
    }

    private static URI uri(String raw) {
        return URI.create(UriUtils.encodePath(raw, StandardCharsets.UTF_8));
    }

    @Test
    void treeListsTopLevelInSortOrder() throws Exception {
        mvc.perform(get("/api/v1/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("home"))
                .andExpect(jsonPath("$[1].path").value("nems"))
                .andExpect(jsonPath("$[1].childCount").value(1));
        mvc.perform(get("/api/v1/tree").param("depth", "2"))
                .andExpect(jsonPath("$[1].children[0].path").value("nems/통계"));
    }

    @Test
    void pageViewIncludesBreadcrumbsTagsLinksAttachments() throws Exception {
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nems/통계"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("통계"))
                .andExpect(jsonPath("$.updatedBy").value("테스터"))
                .andExpect(jsonPath("$.breadcrumbs[0].path").value("nems"))
                .andExpect(jsonPath("$.tags[0]").value("nems"))
                .andExpect(jsonPath("$.links.home.exists").value(true))
                .andExpect(jsonPath("$.links['없는문서'].exists").value(false))
                .andExpect(jsonPath("$.attachments.length()").value(3));
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nems"))
                .andExpect(jsonPath("$.children[0].path").value("nems/통계"));
    }

    @Test
    void missingPageReturnsAliasRedirect() throws Exception {
        mvc.perform(get("/api/v1/pages/by-path").param("path", "old/stat"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.redirect").value("nems/통계"));
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.redirect").doesNotExist());
    }

    @Test
    void outlineAndRecent() throws Exception {
        mvc.perform(get("/api/v1/pages/outline").param("path", "nems/통계"))
                .andExpect(jsonPath("$[0].number").value("1."))
                .andExpect(jsonPath("$[1].number").value("1.1."))
                .andExpect(jsonPath("$[1].title").value("설정"));
        mvc.perform(get("/api/v1/recent"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].source").value("mcp"))
                .andExpect(jsonPath("$[0].deltaBytes").value(2))
                .andExpect(jsonPath("$[0].author").value("테스터"));
    }

    @Test
    void rawMarkdownPermalinkAndSpaShell() throws Exception {
        mvc.perform(get(uri("/w/nems/통계.md")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/markdown")))
                .andExpect(content().string(TestData.STAT_CONTENT));
        mvc.perform(get("/p/2"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/w/nems/%ED%86%B5%EA%B3%84"));
        mvc.perform(get(uri("/w/nems/통계")))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void attachmentHeaders() throws Exception {
        var png = mvc.perform(get(uri("/p/2/files/화면 1.png")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(content().string("PNG"))
                .andReturn();
        String etag = png.getResponse().getHeader("ETag");
        mvc.perform(get(uri("/p/2/files/화면 1.png")).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/p/2/files/d.svg"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"));
        mvc.perform(get("/p/2/files/a.zip"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
        mvc.perform(get("/p/2/files/none.png")).andExpect(status().isNotFound());
    }

    @Test
    void legacyUrlsRedirectPermanently() throws Exception {
        String target = "/w/nems/%ED%86%B5%EA%B3%84";
        mvc.perform(get("/old/stat")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", target));
        mvc.perform(get(uri("/ko/nems/통계"))).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", target));
        mvc.perform(get("/w/old/stat")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", target));
        mvc.perform(get("/unknown-page"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pagebound-test")));
    }

    @Test
    void meIssuesCsrfCookieAndLoginWorks() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.anonymousRead").value(true))
                .andExpect(cookie().exists("XSRF-TOKEN"));
        mvc.perform(post("/login").with(csrf()).param("username", "a@b.c").param("password", "pw"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/login").with(csrf()).param("username", "a@b.c").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/login").param("username", "a@b.c").param("password", "pw"))
                .andExpect(status().isForbidden());
    }
}
