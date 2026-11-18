package io.github.yuchoe.pagebound.core.tag;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.audit.AuditLog;

/**
 * 태그 저장소 (spec/features/F4-tags.md). 관리 작업은 감사 로그에 남긴다 (F12 12절).
 */
@Repository
public class TagStore {

    public record TagCount(long id, String name, int count) {
    }

    public record TaggedPage(String path, String title, String updatedAt) {
    }

    private static final String ACTIVE_PAGE = "p.deleted_at IS NULL AND p.is_draft = 0";

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public TagStore(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
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

    /** 태그 목록 (부분 일치, 사용 수 내림차순). includeUnused=true면 미사용 태그(사용 수 0)도 포함. */
    public List<TagCount> list(String query, int limit, boolean includeUnused) {
        return jdbc.sql("""
                SELECT t.id, t.name, count(p.id) AS cnt FROM tags t
                LEFT JOIN page_tags pt ON pt.tag_id = t.id
                LEFT JOIN pages p ON p.id = pt.page_id AND %s
                WHERE t.name LIKE ? GROUP BY t.id HAVING ? OR cnt > 0
                ORDER BY cnt DESC, t.name COLLATE NOCASE LIMIT ?""".formatted(ACTIVE_PAGE))
                .params("%" + (query == null ? "" : query.trim()) + "%", includeUnused, limit)
                .query((rs, n) -> new TagCount(rs.getLong(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    /** 모든 태그를 가진 문서 (AND). 최근 수정순. */
    public List<TaggedPage> pagesWithAll(List<String> names) {
        List<String> normalized = TagNames.normalizeAll(names);
        if (normalized.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", normalized.stream().map(n -> "?").toList());
        return jdbc.sql("""
                SELECT p.path, p.title, p.updated_at FROM pages p
                JOIN page_tags pt ON pt.page_id = p.id JOIN tags t ON t.id = pt.tag_id
                WHERE %s AND t.name IN (%s) GROUP BY p.id HAVING count(DISTINCT t.id) = ?
                ORDER BY p.updated_at DESC""".formatted(ACTIVE_PAGE, placeholders))
                .params(concat(normalized, normalized.size()))
                .query((rs, n) -> new TaggedPage(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
    }

    @Transactional
    public void rename(long tagId, String newName, long actorId) {
        String old = name(tagId);
        List<String> normalized = TagNames.normalize(newName);
        if (normalized.size() != 1) {
            throw WikiException.badRequest("invalid tag name: " + newName);
        }
        String name = normalized.getFirst();
        Long existing = jdbc.sql("SELECT id FROM tags WHERE name = ?").param(name).query(Long.class).optional().orElse(null);
        if (existing != null && existing != tagId) {
            throw new WikiException(409, "NAME_EXISTS", "tag already exists: " + name, Map.of("tagId", existing));
        }
        jdbc.sql("UPDATE tags SET name = ? WHERE id = ?").params(name, tagId).update();
        audit.record(actorId, "TAG_RENAME", "tag", tagId, Map.of("from", old, "to", name));
    }

    /** source 태그를 target으로 합치고 source 삭제. */
    @Transactional
    public void merge(long sourceId, long targetId, long actorId) {
        if (sourceId == targetId) {
            throw WikiException.badRequest("cannot merge a tag into itself");
        }
        String from = name(sourceId);
        String to = name(targetId);
        jdbc.sql("INSERT OR IGNORE INTO page_tags (page_id, tag_id) SELECT page_id, ? FROM page_tags WHERE tag_id = ?")
                .params(targetId, sourceId).update();
        jdbc.sql("DELETE FROM tags WHERE id = ?").param(sourceId).update();
        audit.record(actorId, "TAG_MERGE", "tag", targetId, Map.of("from", from, "to", to));
    }

    /** 미사용 태그만 삭제 가능 (409 TAG_IN_USE). */
    @Transactional
    public void delete(long tagId, long actorId) {
        String name = name(tagId);
        int used = jdbc.sql("SELECT count(*) FROM page_tags WHERE tag_id = ?").param(tagId).query(Integer.class).single();
        if (used > 0) {
            throw new WikiException(409, "TAG_IN_USE", "tag is used by " + used + " pages", Map.of("count", used));
        }
        jdbc.sql("DELETE FROM tags WHERE id = ?").param(tagId).update();
        audit.record(actorId, "TAG_DELETE", "tag", tagId, Map.of("name", name));
    }

    private String name(long tagId) {
        return jdbc.sql("SELECT name FROM tags WHERE id = ?").param(tagId).query(String.class).optional()
                .orElseThrow(() -> WikiException.notFound("tag " + tagId));
    }

    private static Object[] concat(List<String> names, int count) {
        Object[] params = new Object[names.size() + 1];
        for (int i = 0; i < names.size(); i++) {
            params[i] = names.get(i);
        }
        params[names.size()] = count;
        return params;
    }
}
