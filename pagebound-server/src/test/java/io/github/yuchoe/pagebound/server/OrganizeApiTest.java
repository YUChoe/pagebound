package io.github.yuchoe.pagebound.server;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.server.web.LegacyRedirectFilter;

/**
 * M4 API: 이동·휴지통·태그 관리·검색 (spec/phases/M4-organize.md).
 */
@SpringBootTest(properties = "pagebound.data-dir=target/it-organize")
class OrganizeApiTest {

    private static final RequestPostProcessor EDITOR = user("a@b.c").roles("EDITOR");
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
    LegacyRedirectFilter legacyRedirect;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(legacyRedirect).apply(springSecurity()).build();
        TestData.seed(jdbc, links, storage, encoder);
    }

    @Test
    void moveRedirectsOldUrlAndTrashNeedsAdminToRestore() throws Exception {
        mvc.perform(post("/api/v1/pages/3/move").with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentPath\":\"nems\",\"slug\":\"홈\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("nems/홈"))
                .andExpect(jsonPath("$.linkUpdatedPages").value(1));
        mvc.perform(get("/w/home")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/w/nems/%ED%99%88"));
        // nems/통계의 [[home]] 링크가 새 경로로 바뀜
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nems/통계"))
                .andExpect(jsonPath("$.content").value(containsString("[[nems/홈]]")));

        mvc.perform(delete("/api/v1/pages/1").with(csrf()).with(EDITOR))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HAS_CHILDREN"));
        mvc.perform(delete("/api/v1/pages/1").param("includeChildren", "true").with(csrf()).with(EDITOR))
                .andExpect(jsonPath("$.pages").value(3));
        mvc.perform(get("/api/v1/admin/trash").with(EDITOR)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/trash").with(ADMIN))
                .andExpect(jsonPath("$[0].path").value("nems"))
                .andExpect(jsonPath("$[0].pageCount").value(3));
        mvc.perform(post("/api/v1/admin/trash/1/restore").with(csrf()).with(ADMIN))
                .andExpect(jsonPath("$.path").value("nems"));
        mvc.perform(get("/api/v1/pages/by-path").param("path", "nems/통계")).andExpect(status().isOk());
    }

    @Test
    void tagAdminRequiresAdmin() throws Exception {
        mvc.perform(patch("/api/v1/tags/1").with(csrf()).with(EDITOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"EMS\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/tags/1").with(csrf()).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"EMS\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tags/pages").param("names", "ems"))
                .andExpect(jsonPath("$[0].path").value("nems/통계"));
        mvc.perform(delete("/api/v1/tags/1").with(csrf()).with(ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAG_IN_USE"));
    }

    @Test
    void searchAndSuggest() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "본문"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].path").value("nems/통계"))
                .andExpect(jsonPath("$.items[0].snippets[0].text").value(containsString("\u0002본문\u0003")))
                .andExpect(jsonPath("$.items[0].section.number").value("1.1."))
                .andExpect(jsonPath("$.tagFacets[0].name").value("nems"));
        mvc.perform(get("/api/v1/search/suggest").param("q", "통계"))
                .andExpect(jsonPath("$[0].path").value("nems/통계"))
                .andExpect(jsonPath("$[0].exact").value(true));
    }
}
