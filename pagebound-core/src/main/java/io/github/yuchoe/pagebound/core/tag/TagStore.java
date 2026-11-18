package io.github.yuchoe.pagebound.core.tag;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 태그 저장소 (spec/features/F4-tags.md).
 */
@Repository
public class TagStore {

    public record TagCount(String name, int count) {
    }

    private final JdbcClient jdbc;

    public TagStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 문서 태그 전체 교체. 원문은 TagNames 규칙으로 정규화한다. */
    public void replace(long pageId, List<String> raw) {
        jdbc.sql("DELETE FROM page_tags WHERE page_id = ?").param(pageId).update();
        for (String name : TagNames.normalizeAll(raw)) {
            Long tagId = jdbc.sql("SELECT id FROM tags WHERE name = ?").param(name).query(Long.class).optional()
                    .orElseGet(() -> {
                        jdbc.sql("INSERT INTO tags (name) VALUES (?)").param(name).update();
                        return jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
                    });
            jdbc.sql("INSERT OR IGNORE INTO page_tags (page_id, tag_id) VALUES (?, ?)").params(pageId, tagId).update();
        }
    }

    /** 사용 중인 태그 (부분 일치, 사용 수 내림차순). */
    public List<TagCount> list(String query, int limit) {
        return jdbc.sql("""
                SELECT t.name, count(pt.page_id) AS cnt FROM tags t
                JOIN page_tags pt ON pt.tag_id = t.id JOIN pages p ON p.id = pt.page_id
                WHERE p.deleted_at IS NULL AND p.is_draft = 0 AND t.name LIKE ?
                GROUP BY t.id ORDER BY cnt DESC, t.name COLLATE NOCASE LIMIT ?""")
                .params("%" + (query == null ? "" : query.trim()) + "%", limit)
                .query((rs, n) -> new TagCount(rs.getString(1), rs.getInt(2)))
                .list();
    }
}
