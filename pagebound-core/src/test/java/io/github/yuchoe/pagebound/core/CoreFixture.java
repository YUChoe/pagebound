package io.github.yuchoe.pagebound.core;

import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.audit.AuditLog;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.page.PageStore;
import io.github.yuchoe.pagebound.core.page.PageTreeService;
import io.github.yuchoe.pagebound.core.page.PageWriteService;
import io.github.yuchoe.pagebound.core.search.SearchService;
import io.github.yuchoe.pagebound.core.tag.TagStore;

/**
 * core 테스트 공용: 임시 SQLite + Flyway + 서비스 조립. 사용자 1(EDITOR) 포함. 사용 후 close() 필수(Windows 파일 잠금).
 */
public final class CoreFixture implements AutoCloseable {

    public final SingleConnectionDataSource dataSource;
    public final JdbcClient jdbc;
    public final AttachmentStorage storage;
    public final AuditLog audit;
    public final PageStore pages;
    public final LinkIndexer links;
    public final TagStore tags;
    public final PageWriteService writes;
    public final PageTreeService tree;
    public final SearchService search;

    public CoreFixture(Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("t.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, null, null).load().migrate();
        dataSource = new SingleConnectionDataSource(url, true);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO users (id, email, name, password_hash, role, created_at, updated_at) "
                + "VALUES (1, 'a@b.c', '편집자', 'x', 'EDITOR', 't', 't')").update();
        storage = new AttachmentStorage(dir);
        audit = new AuditLog(jdbc);
        pages = new PageStore(jdbc);
        links = new LinkIndexer(jdbc, pages);
        tags = new TagStore(jdbc, audit);
        writes = new PageWriteService(jdbc, pages, links, tags, storage);
        tree = new PageTreeService(jdbc, pages, links, storage, audit);
        search = new SearchService(jdbc);
    }

    public long page(String path, String content, String... tagNames) {
        return writes.create(path, null, content, List.of(tagNames), null, true, 1, "web").id();
    }

    public String content(long id) {
        return jdbc.sql("SELECT content FROM pages WHERE id = ?").param(id).query(String.class).single();
    }

    public String path(long id) {
        return jdbc.sql("SELECT path FROM pages WHERE id = ?").param(id).query(String.class).single();
    }

    @Override
    public void close() {
        dataSource.destroy();
    }
}
