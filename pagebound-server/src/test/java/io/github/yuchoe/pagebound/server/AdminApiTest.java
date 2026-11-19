package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 기능 (spec/features/F12-admin.md 15절 완료 기준).
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-admin")
class AdminApiTest {

    /** DB상 사용자 1(EDITOR)이지만 세션 권한은 ADMIN인 행위자 */
    private static final RequestPostProcessor ADMIN = user("a@b.c").roles("ADMIN");

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
    RuntimeSettings settings;
    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.seed(jdbc, links, storage, encoder);
        settings.reload();
        jdbc.sql("""
                INSERT INTO users (id, email, name, password_hash, role, created_at, updated_at)
                VALUES (5, 'admin@b.c', '관리자', ?, 'ADMIN', 't', 't')""").param(encoder.encode("adminpw1")).update();
    }

    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/login").user(email).password(password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test
    void onlyAdminsReachAdminApi() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/users").with(user("a@b.c").roles("EDITOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").with(user("a@b.c").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/dashboard").with(ADMIN))
                .andExpect(jsonPath("$.pages.active").value(3))
                .andExpect(jsonPath("$.attachments.count").value(3))
                .andExpect(jsonPath("$.users.activeAdmins").value(1));
    }

    @Test
    void userActivityListsRecentEditsAndTokens() throws Exception {
        jdbc.sql("INSERT INTO api_tokens (user_id, name, token_hash, scope, created_at) VALUES (1, 'cli', 'h', 'read', 't')")
                .update();
        mvc.perform(get("/api/v1/admin/users/1/activity").with(ADMIN))
                .andExpect(jsonPath("$.edits[0].path").value("nems/통계"))
                .andExpect(jsonPath("$.edits[0].version").value(2))
                .andExpect(jsonPath("$.edits[0].source").value("mcp"))
                .andExpect(jsonPath("$.tokens[0].name").value("cli"));
        mvc.perform(get("/api/v1/admin/users/1/activity").with(user("a@b.c").roles("EDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void lastAdminAndSelfAreProtected() throws Exception {
        mvc.perform(patch("/api/v1/admin/users/5").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        MockHttpSession admin = login("admin@b.c", "adminpw1");
        mvc.perform(patch("/api/v1/admin/users/5").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
    }

    @Test
    void createdUserLogsInAndDeactivationEndsSession() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/users").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@b.c\",\"name\":\"신규\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var created = json.readTree(body);
        String temp = created.get("temporaryPassword").asString();
        assertThat(temp).hasSize(16);

        MockHttpSession session = login("new@b.c", temp);
        mvc.perform(get("/api/v1/me").session(session)).andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/pages/2/source").session(session)).andExpect(status().isOk());

        long id = created.get("id").asLong();
        mvc.perform(patch("/api/v1/admin/users/" + id).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/pages/2/source").session(session)).andExpect(status().isUnauthorized());

        String audit = jdbc.sql("SELECT group_concat(action || ':' || coalesce(detail, ''), '|') FROM audit_log")
                .query(String.class).single();
        assertThat(audit).contains("USER_CREATE", "USER_DEACTIVATE").doesNotContain(temp);
    }

    @Test
    void passwordResetAndOwnChange() throws Exception {
        String temp = json.readTree(mvc.perform(post("/api/v1/admin/users/1/reset-password").with(ADMIN).with(csrf()))
                .andReturn().getResponse().getContentAsString()).get("temporaryPassword").asString();
        MockHttpSession session = login("a@b.c", temp);
        mvc.perform(post("/api/v1/me/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":\"wrong\",\"next\":\"newpassword\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":\"" + temp + "\",\"next\":\"newpassword\"}"))
                .andExpect(status().isNoContent());
        login("a@b.c", "newpassword");
    }

    @Test
    void runtimeSettingsApplyImmediately() throws Exception {
        mvc.perform(get("/api/v1/tree")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/settings").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"security.anonymous-read\":\"false\",\"site.name\":\"팀 위키\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tree")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me")).andExpect(jsonPath("$.siteName").value("팀 위키"))
                .andExpect(jsonPath("$.anonymousRead").value(false));
        mvc.perform(put("/api/v1/admin/settings").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upload.max-file-size\":\"100MB\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/settings").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"security.anonymous-read\":\"\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tree")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/maintenance/rebuild-links").with(ADMIN).with(csrf()))
                .andExpect(jsonPath("$.task").value("rebuild-links"));
        mvc.perform(get("/api/v1/admin/cleanup/red-links").with(ADMIN))
                .andExpect(jsonPath("$[0].target").value("없는문서"));
    }
}
