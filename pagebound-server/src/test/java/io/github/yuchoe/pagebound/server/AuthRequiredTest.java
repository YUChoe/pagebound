package io.github.yuchoe.pagebound.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;

/**
 * 익명 읽기 차단(pagebound.anonymous-read=false): 데이터는 로그인 필요, SPA 셸은 공개.
 */
@SpringBootTest(properties = {"pagebound.data-dir=target/it-auth", "pagebound.anonymous-read=false"})
class AuthRequiredTest {

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
    void dataRequiresLoginButShellIsPublic() throws Exception {
        mvc.perform(get("/api/v1/tree")).andExpect(status().isUnauthorized());
        mvc.perform(get("/p/2/files/d.svg")).andExpect(status().isUnauthorized());
        mvc.perform(get("/w/home.md")).andExpect(status().isUnauthorized());
        mvc.perform(get("/w/home")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/v1/me")).andExpect(jsonPath("$.anonymousRead").value(false));

        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("username", "A@B.C").param("password", "pw"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tree").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").session(session))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.role").value("EDITOR"));
    }
}
