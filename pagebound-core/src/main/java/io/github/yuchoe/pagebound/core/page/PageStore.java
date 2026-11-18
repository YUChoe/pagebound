package io.github.yuchoe.pagebound.core.page;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * pages / page_revisions / page_aliases 기본 저장소.
 */
@Repository
public class PageStore {

    private final JdbcClient jdbc;

    public PageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** id가 null이면 자동 생성. path = 부모 path + slug. */
    public record NewPage(Long id, Long parentId, String slug, String title, String content, int sortOrder,
                          int version, Long createdBy, Long updatedBy, String createdAt, String updatedAt) {
    }

    public record NewRevision(long pageId, int version, String title, String path, String content, String action,
                              String source, String summary, Long authorId, Long tokenId, String createdAt) {
    }

    public Optional<String> pathOf(long pageId) {
        return jdbc.sql("SELECT path FROM pages WHERE id = ?").param(pageId).query(String.class).optional();
    }

    /** 활성 문서(휴지통·초안 제외)를 경로로 찾는다. */
    public Optional<Long> findActiveIdByPath(String path) {
        return jdbc.sql("SELECT id FROM pages WHERE path = ? AND deleted_at IS NULL AND is_draft = 0")
                .param(path).query(Long.class).optional();
    }

    public Optional<Long> findIdByAlias(String path) {
        return jdbc.sql("SELECT page_id FROM page_aliases WHERE path = ?").param(path).query(Long.class).optional();
    }

    /** 문서를 추가하고 id를 반환한다. 부모가 없으면 IllegalArgumentException. */
    public long insert(NewPage p) {
        if (!WikiPaths.isValidSlug(p.slug())) {
            throw new IllegalArgumentException("invalid slug: " + p.slug());
        }
        String parentPath = "";
        if (p.parentId() != null) {
            parentPath = pathOf(p.parentId())
                    .orElseThrow(() -> new IllegalArgumentException("parent not found: " + p.parentId()));
        }
        String path = WikiPaths.join(parentPath, p.slug());
        jdbc.sql("""
                INSERT INTO pages (id, parent_id, slug, path, title, content, sort_order, version,
                                   created_by, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(p.id(), p.parentId(), p.slug(), path, p.title(), p.content(), p.sortOrder(), p.version(),
                        p.createdBy(), p.updatedBy(), p.createdAt(), p.updatedAt())
                .update();
        return p.id() != null ? p.id()
                : jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
    }

    public void insertRevision(NewRevision r) {
        jdbc.sql("""
                INSERT INTO page_revisions (page_id, version, title, path, content, action, source, summary,
                                            author_id, token_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.pageId(), r.version(), r.title(), r.path(), r.content(), r.action(), r.source(),
                        r.summary(), r.authorId(), r.tokenId(), r.createdAt())
                .update();
    }

    public void insertAlias(String path, long pageId) {
        jdbc.sql("INSERT OR REPLACE INTO page_aliases (path, page_id) VALUES (?, ?)").params(path, pageId).update();
    }
}
