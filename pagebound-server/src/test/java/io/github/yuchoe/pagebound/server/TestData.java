package io.github.yuchoe.pagebound.server;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;

/**
 * 통합 테스트 공용 데이터: nems / nems/통계 / home, alias, 첨부 3개, 리비전 2개, 사용자 1명(a@b.c / pw).
 */
final class TestData {

    static final String STAT_CONTENT = "# 개요\n[[home]] [[없는문서]]\n![](/p/2/files/%ED%99%94%EB%A9%B4%201.png)\n## 설정\n본문\n";

    private TestData() {
    }

    static void seed(JdbcClient jdbc, LinkIndexer links, AttachmentStorage storage, PasswordEncoder encoder) {
        // parent_id는 ON DELETE RESTRICT이고 SQLite는 행 단위로 즉시 검사하므로 연결을 먼저 끊는다
        jdbc.sql("UPDATE pages SET parent_id = NULL").update();
        for (String table : List.of("audit_log", "page_links", "page_tags", "tags", "attachments", "page_aliases",
                "page_revisions", "api_tokens", "link_previews", "pages", "users")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("""
                INSERT INTO users (id, email, name, password_hash, role, created_at, updated_at)
                VALUES (1, 'a@b.c', '테스터', ?, 'EDITOR', 't', 't')""").param(encoder.encode("pw")).update();
        page(jdbc, 1, null, "nems", "nems", "", 1);
        page(jdbc, 2, 1L, "통계", "nems/통계", STAT_CONTENT, 0);
        page(jdbc, 3, null, "home", "home", "홈", 0);
        jdbc.sql("INSERT INTO page_aliases (path, page_id) VALUES ('old/stat', 2), ('nems/통계', 2)").update();
        jdbc.sql("INSERT INTO tags (id, name) VALUES (1, 'nems')").update();
        jdbc.sql("INSERT INTO page_tags (page_id, tag_id) VALUES (2, 1)").update();
        attachment(jdbc, storage, 1, "화면 1.png", "image/png", "PNG");
        attachment(jdbc, storage, 2, "d.svg", "image/svg+xml", "<svg/>");
        attachment(jdbc, storage, 3, "a.zip", "application/zip", "ZIP");
        jdbc.sql("""
                INSERT INTO page_revisions (page_id, version, title, path, content, action, source, author_id, created_at)
                VALUES (2, 1, '통계', 'nems/통계', 'a', 'created', 'web', 1, '2026-01-01T00:00:00Z'),
                       (2, 2, '통계', 'nems/통계', 'abc', 'updated', 'mcp', 1, '2026-01-02T00:00:00Z')""").update();
        jdbc.sql("UPDATE pages SET version = 2 WHERE id = 2").update(); // 리비전 v1·v2와 일치
        links.rebuildAll();
    }

    private static void page(JdbcClient jdbc, long id, Long parentId, String slug, String path, String content,
                             int sortOrder) {
        jdbc.sql("""
                INSERT INTO pages (id, parent_id, slug, path, title, content, sort_order, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 't', 't')""")
                .params(id, parentId, slug, path, slug, content, sortOrder).update();
    }

    private static void attachment(JdbcClient jdbc, AttachmentStorage storage, long id, String name, String mime,
                                   String data) {
        byte[] bytes = data.getBytes();
        jdbc.sql("INSERT INTO attachments (id, page_id, filename, mime, size, sha256, created_at) VALUES (?, 2, ?, ?, ?, ?, 't')")
                .params(id, name, mime, bytes.length, AttachmentStorage.sha256(bytes)).update();
        storage.write(2, id, bytes);
    }
}
