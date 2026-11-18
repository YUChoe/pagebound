package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.token.ApiTokenService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토큰 발급·폐기와 Bearer REST 인증 (spec/phases/M5-mcp.md 1~3).
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-token")
class TokenApiTest {

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
    ApiTokenService tokens;
    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.seed(jdbc, links, storage, encoder);
    }

    @Test
    void issueOnceListRevoke() throws Exception {
        String body = mvc.perform(post("/api/v1/me/tokens").with(user("a@b.c").roles("EDITOR")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"cli\",\"scope\":\"write\",\"expiresInDays\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var issued = json.readTree(body);
        String token = issued.get("token").asString();
        assertThat(token).startsWith("pb_").hasSize(46);
        assertThat(jdbc.sql("SELECT token_hash FROM api_tokens").query(String.class).single()).doesNotContain(token);

        mvc.perform(get("/api/v1/me/tokens").with(user("a@b.c").roles("EDITOR")))
                .andExpect(jsonPath("$[0].name").value("cli"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
        // 토큰으로 토큰 관리 불가, 관리자 API 불가
        mvc.perform(get("/api/v1/me/tokens").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tokens").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/me/tokens/" + issued.get("id").asLong()).with(user("a@b.c").roles("EDITOR")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/recent").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocsListV1Endpoints() throws Exception {
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/pages/{id}']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    @Test
    void bearerWritesWithoutCsrfAndRecordsApiSource() throws Exception {
        var write = tokens.issue(1, "ci", "write", null);
        mvc.perform(post("/api/v1/pages").header("Authorization", "Bearer " + write.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"토큰문서\",\"content\":\"x\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT source || ':' || token_id FROM page_revisions r JOIN pages p ON p.id = r.page_id "
                + "WHERE p.path = '토큰문서'").query(String.class).single()).isEqualTo("api:" + write.id());

        var read = tokens.issue(1, "ro", "read", null);
        mvc.perform(post("/api/v1/pages").header("Authorization", "Bearer " + read.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"읽기전용\",\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pages/2/source").header("Authorization", "Bearer " + read.token()))
                .andExpect(status().isForbidden());

        // 비활성 사용자의 토큰은 무효
        jdbc.sql("UPDATE users SET is_active = 0 WHERE id = 1").update();
        mvc.perform(get("/api/v1/recent").header("Authorization", "Bearer " + write.token()))
                .andExpect(status().isUnauthorized());
    }
}
