package io.github.yuchoe.pagebound.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;

/**
 * 쓰기 API 권한과 동작 (spec/phases/M3-write.md).
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-write")
class PageWriteApiTest {

    private static final RequestPostProcessor EDITOR = user("a@b.c").roles("EDITOR");

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

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.seed(jdbc, links, storage, encoder);
    }

    @Test
    void writesRequireEditorRole() throws Exception {
        String body = "{\"path\":\"새문서\",\"content\":\"x\"}";
        mvc.perform(post("/api/v1/pages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/pages").with(csrf()).with(user("v@b.c").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pages/2/source")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/pages").with(EDITOR).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // CSRF 토큰 없음
    }

    @Test
    void createRedLinkTargetThenUpdateWithConflict() throws Exception {
        mvc.perform(post("/api/v1/pages").with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"없는문서\",\"content\":\"# 제목\\n본문\",\"tags\":[\"새\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("없는문서"))
                .andExpect(jsonPath("$.version").value(1));
        // nems/통계 의 레드 링크 [[없는문서]] 가 파란 링크가 됨
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nems/통계"))
                .andExpect(jsonPath("$.links['없는문서'].exists").value(true));

        mvc.perform(get("/api/v1/pages/2/source").with(EDITOR).param("section", "2"))
                .andExpect(jsonPath("$.content").value("## 설정\n본문\n"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put("/api/v1/pages/2").with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"content\":\"## 설정\\n바뀜\",\"section\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.warnings").isEmpty());
        mvc.perform(put("/api/v1/pages/2").with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"content\":\"덮어쓰기\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(3));
        mvc.perform(get("/api/v1/tags").param("q", "ne"))
                .andExpect(jsonPath("$[0].name").value("nems"));
    }

    @Test
    void uploadAddsSuffixAndDraftFlow() throws Exception {
        var file = new MockMultipartFile("file", "화면 1.png", "image/png", "NEW".getBytes());
        mvc.perform(multipart("/api/v1/pages/2/attachments").file(file).with(csrf()).with(EDITOR))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("화면 1-1.png"))
                .andExpect(jsonPath("$.url").value("/p/2/files/%ED%99%94%EB%A9%B4%201-1.png"));
        mvc.perform(get("/api/v1/pages/2/attachments").with(EDITOR))
                .andExpect(jsonPath("$[?(@.filename == '화면 1.png')].referenced").value(true))
                .andExpect(jsonPath("$[?(@.filename == 'a.zip')].referenced").value(false));

        var draft = mvc.perform(post("/api/v1/pages/drafts").with(csrf()).with(EDITOR))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(draft.replaceAll("\\D", ""));
        mvc.perform(multipart("/api/v1/pages/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "image.png", "image/png", "IMG".getBytes()))
                        .with(csrf()).with(EDITOR))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/pages/" + id).with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"content\":\"![](/p/" + id + "/files/image.png)\","
                                + "\"path\":\"tip/스크린샷\",\"createParents\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("tip/스크린샷"));
        mvc.perform(get("/api/v1/pages/by-path").param("path", "tip/스크린샷"))
                .andExpect(jsonPath("$.attachments[0].referenced").value(true));
    }
}
