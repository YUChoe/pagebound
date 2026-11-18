package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;

/**
 * 역사 API (spec/features/F7-common.md 2절, 6절 완료 기준 "복원").
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-history")
class HistoryApiTest {

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
    void listDiffAndRestore() throws Exception {
        mvc.perform(get("/api/v1/pages/2/revisions"))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].source").value("mcp"))
                .andExpect(jsonPath("$[0].deltaBytes").value(2));
        mvc.perform(get("/api/v1/pages/2/diff").param("from", "1").param("to", "2"))
                .andExpect(jsonPath("$.rows[0].type").value("change"))
                .andExpect(jsonPath("$.rows[0].newText").value("abc"));

        mvc.perform(post("/api/v1/pages/2/revisions/1/restore").with(csrf()).with(user("v@b.c").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pages/2/revisions/1/restore").with(csrf()).with(user("a@b.c").roles("EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        assertThat(jdbc.sql("SELECT content FROM pages WHERE id = 2").query(String.class).single()).isEqualTo("a");
        mvc.perform(get("/api/v1/pages/2/revisions/3")).andExpect(jsonPath("$.content").value("a"));
        mvc.perform(get("/api/v1/pages/2/revisions/99")).andExpect(status().isNotFound());
    }
}
