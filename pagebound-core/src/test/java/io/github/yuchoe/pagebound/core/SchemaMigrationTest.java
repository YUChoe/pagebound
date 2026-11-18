package io.github.yuchoe.pagebound.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V1 스키마가 SQLite에 적용되고 PRAGMA, FTS 트리거, 제약이 동작하는지 검증한다.
 */
class SchemaMigrationTest {

    @TempDir
    Path tempDir;

    private String url;

    @BeforeEach
    void migrate() {
        url = "jdbc:sqlite:" + tempDir.resolve("test.db") + "?foreign_keys=true&journal_mode=WAL&busy_timeout=5000";
        Flyway.configure().dataSource(url, null, null).load().migrate();
    }

    @Test
    void createsAllTables() throws SQLException {
        List<String> tables = queryStrings(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name NOT LIKE 'pages_fts_%' AND name <> 'flyway_schema_history' ORDER BY name");
        assertThat(tables).containsExactly(
                "api_tokens", "attachments", "audit_log", "link_previews", "page_aliases", "page_blame", "page_links",
                "page_revisions", "page_tags", "pages", "pages_fts", "tags", "users");
    }

    @Test
    void urlPragmasApplied() throws SQLException {
        assertThat(queryStrings("PRAGMA foreign_keys")).containsExactly("1");
        assertThat(queryStrings("PRAGMA journal_mode")).containsExactly("wal");
    }

    @Test
    void ftsTriggersSyncKoreanTrigram() throws SQLException {
        exec("INSERT INTO pages (id, slug, path, title, content, created_at, updated_at) "
                + "VALUES (1, '통계', 'nems/통계', '통계', '파티션 테이블 설명', '2026-11-18', '2026-11-18')");
        assertThat(queryStrings("SELECT rowid FROM pages_fts WHERE pages_fts MATCH '파티션'")).containsExactly("1");

        exec("UPDATE pages SET content = '알티베이스 설정' WHERE id = 1");
        assertThat(queryStrings("SELECT rowid FROM pages_fts WHERE pages_fts MATCH '파티션'")).isEmpty();
        assertThat(queryStrings("SELECT rowid FROM pages_fts WHERE pages_fts MATCH '알티베'")).containsExactly("1");

        exec("DELETE FROM pages WHERE id = 1");
        assertThat(queryStrings("SELECT rowid FROM pages_fts WHERE pages_fts MATCH '알티베'")).isEmpty();
    }

    @Test
    void attachmentRequiresExistingPageAndCascades() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO attachments (page_id, filename, mime, size, sha256, created_at) "
                + "VALUES (999, 'a.png', 'image/png', 1, 'x', '2026-11-18')"))
                .isInstanceOf(SQLException.class);

        exec("INSERT INTO pages (id, slug, path, title, created_at, updated_at) VALUES (1, 'a', 'a', 'a', 't', 't')");
        exec("INSERT INTO attachments (page_id, filename, mime, size, sha256, created_at) "
                + "VALUES (1, 'a.png', 'image/png', 1, 'x', 't')");
        exec("DELETE FROM pages WHERE id = 1");
        assertThat(queryStrings("SELECT count(*) FROM attachments")).containsExactly("0");
    }

    @Test
    void activePathUniqueButTrashedPathReusable() throws SQLException {
        exec("INSERT INTO pages (slug, path, title, created_at, updated_at, deleted_at) VALUES ('a', 'a', 'a', 't', 't', 't')");
        exec("INSERT INTO pages (slug, path, title, created_at, updated_at) VALUES ('a', 'a', 'a', 't', 't')");
        assertThatThrownBy(() -> exec("INSERT INTO pages (slug, path, title, created_at, updated_at) "
                + "VALUES ('a', 'a', 'a', 't', 't')"))
                .isInstanceOf(SQLException.class);
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
