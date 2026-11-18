package io.github.yuchoe.pagebound.core.page;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.audit.AuditLog;
import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;

/**
 * 트리 편집: 이동·이름 변경·순서, 휴지통·복원·영구 삭제 (spec/features/F3-tree-links.md 4·6절, F12-admin.md 6절).
 */
@Service
public class PageTreeService {

    private static final String SUBTREE = """
            WITH RECURSIVE sub(id) AS (SELECT ? UNION ALL SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id)
            """;

    public record Moved(long id, String path, int version, int linkUpdatedPages) {
    }

    public record TrashItem(long id, String title, String path, String deletedAt, String deletedBy, String purgeAt,
                            int pageCount, long attachmentBytes) {
    }

    private record Row(long id, Long parentId, String slug, String path, String title, String content, int version,
                       String deletedAt) {
    }

    private final JdbcClient jdbc;
    private final PageStore pages;
    private final LinkIndexer links;
    private final AttachmentStorage storage;
    private final AuditLog audit;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public PageTreeService(JdbcClient jdbc, PageStore pages, LinkIndexer links, AttachmentStorage storage,
                           AuditLog audit) {
        this.jdbc = jdbc;
        this.pages = pages;
        this.links = links;
        this.storage = storage;
        this.audit = audit;
    }

    /**
     * 이동/이름 변경/순서 변경. newParentPath: null=부모 유지, ""=최상위. newSlug·newTitle: null=유지.
     * position: 형제 사이 위치(0부터), null이면 부모가 같을 때 유지·다를 때 맨 뒤.
     */
    @Transactional
    public Moved move(long id, String newParentPath, String newSlug, String newTitle, Integer position,
                      long actorId, String source) {
        Row row = load(id);
        if (row.deletedAt() != null) {
            throw WikiException.notFound("page " + id);
        }
        String oldPath = row.path();
        String parentPath = newParentPath == null ? WikiPaths.parentOf(oldPath) : WikiPaths.normalize(newParentPath);
        Long parentId = parentPath.isEmpty() ? null : pages.findActiveIdByPath(parentPath)
                .orElseThrow(() -> new WikiException(409, "PARENT_NOT_FOUND", "parent page not found: " + parentPath,
                        Map.of("parent", parentPath)));
        if (parentPath.equals(oldPath) || parentPath.startsWith(oldPath + "/")) {
            throw new WikiException(409, "CYCLE", "cannot move a page under itself");
        }
        String slug = newSlug == null ? row.slug() : newSlug.trim();
        if (!WikiPaths.isValidSlug(slug)) {
            throw WikiException.badRequest("invalid slug: " + slug);
        }
        String title = newTitle == null || newTitle.isBlank() ? row.title() : newTitle.trim();
        String newPath = WikiPaths.join(parentPath, slug);
        boolean sameParent = java.util.Objects.equals(parentId, row.parentId());
        if (!newPath.equals(oldPath) && pages.findActiveIdByPath(newPath).isPresent()) {
            throw new WikiException(409, "PATH_EXISTS", "page already exists: " + newPath);
        }
        if (newPath.equals(oldPath) && title.equals(row.title())) {
            setPosition(parentId, id, position);
            return new Moved(id, oldPath, row.version(), 0);
        }

        // 하위 트리 경로 매핑 (parent_id 체인 기준)
        Map<Long, String[]> subtree = new LinkedHashMap<>();
        Map<String, String> pathMap = new LinkedHashMap<>();
        jdbc.sql(SUBTREE + "SELECT id, path FROM pages WHERE id IN (SELECT id FROM sub)").param(id)
                .query((rs, n) -> {
                    String old = rs.getString(2);
                    String moved = newPath + old.substring(oldPath.length());
                    subtree.put(rs.getLong(1), new String[] {old, moved});
                    pathMap.put(old, moved);
                    return null;
                }).list();

        // 링크 재작성 대상: 이동하는 문서를 가리키는 활성 문서 + 이동하는 문서 자신(상대 링크)
        Set<Long> sources = new LinkedHashSet<>(subtree.keySet());
        sources.addAll(jdbc.sql(SUBTREE + """
                SELECT DISTINCT l.source_page_id FROM page_links l JOIN pages s ON s.id = l.source_page_id
                WHERE l.target_page_id IN (SELECT id FROM sub) AND s.deleted_at IS NULL""").param(id)
                .query(Long.class).list());
        Map<Long, String> rewritten = new LinkedHashMap<>();
        for (long sourceId : sources) {
            Row src = load(sourceId);
            String srcOld = subtree.containsKey(sourceId) ? subtree.get(sourceId)[0] : src.path();
            String srcNew = subtree.containsKey(sourceId) ? subtree.get(sourceId)[1] : src.path();
            String content = rewriteLinks(src.content(), srcOld, srcNew, pathMap);
            if (!content.equals(src.content())) {
                rewritten.put(sourceId, content);
            }
        }

        String now = Instant.now().toString();
        subtree.forEach((pid, p) -> jdbc.sql("UPDATE pages SET path = ? WHERE id = ?").params(p[1], pid).update());
        int version = row.version() + 1;
        String content = rewritten.getOrDefault(id, row.content());
        jdbc.sql("""
                UPDATE pages SET parent_id = ?, slug = ?, title = ?, content = ?, version = ?, updated_by = ?,
                       updated_at = ? WHERE id = ?""")
                .params(parentId, slug, title, content, version, actorId, now, id).update();
        setPosition(parentId, id, position == null && !sameParent ? Integer.MAX_VALUE : position);
        pages.insertRevision(new PageStore.NewRevision(id, version, title, newPath, content,
                newPath.equals(oldPath) ? "updated" : "moved", source, null, actorId, null, now));

        subtree.forEach((pid, p) -> {
            if (!p[0].equals(p[1])) {
                jdbc.sql("DELETE FROM page_aliases WHERE path = ?").param(p[1]).update();
                pages.insertAlias(p[0], pid);
            }
        });
        int linkUpdated = 0;
        for (Map.Entry<Long, String> e : rewritten.entrySet()) {
            if (e.getKey() == id) {
                continue;
            }
            Row src = load(e.getKey());
            int v = src.version() + 1;
            jdbc.sql("UPDATE pages SET content = ?, version = ?, updated_at = ? WHERE id = ?")
                    .params(e.getValue(), v, now, src.id()).update();
            pages.insertRevision(new PageStore.NewRevision(src.id(), v, src.title(), src.path(), e.getValue(),
                    "link-updated", "system", "문서 이동에 따른 링크 갱신: " + oldPath + " → " + newPath,
                    actorId, null, now));
            linkUpdated++;
        }
        for (long sourceId : sources) {
            links.rebuild(sourceId);
        }
        subtree.forEach((pid, p) -> jdbc.sql(
                "UPDATE page_links SET target_page_id = ? WHERE target_path = ? AND target_page_id IS NULL")
                .params(pid, p[1]).update());
        return new Moved(id, newPath, version, linkUpdated);
    }

    /** 이동 전 경로로 해석한 링크 대상이 이동 후에도 같은 문서를 가리키도록 필요한 링크만 절대 경로로 고친다. */
    String rewriteLinks(String content, String oldSourcePath, String newSourcePath, Map<String, String> pathMap) {
        List<MarkdownAnalyzer.WikiLink> found = analyzer.wikiLinks(content, oldSourcePath);
        StringBuilder out = new StringBuilder(content);
        for (int i = found.size() - 1; i >= 0; i--) {
            MarkdownAnalyzer.WikiLink link = found.get(i);
            if (link.path().isEmpty()) {
                continue;
            }
            String target = pathMap.getOrDefault(link.path(), link.path());
            if (WikiPaths.resolve(newSourcePath, link.rawTarget()).equals(target)) {
                continue;
            }
            String replacement = "[[" + target + (link.fragment().isEmpty() ? "" : "#" + link.fragment())
                    + (link.label() == null ? "" : "|" + link.label()) + "]]";
            out.replace(link.start(), link.end(), replacement);
        }
        return out.toString();
    }

    /** 휴지통으로. 활성 하위 문서가 있으면 includeChildren=true여야 한다 (409 HAS_CHILDREN). */
    @Transactional
    public int trash(long id, boolean includeChildren, long actorId) {
        Row row = load(id);
        if (row.deletedAt() != null) {
            throw WikiException.notFound("page " + id);
        }
        List<Long> ids = jdbc.sql(SUBTREE + "SELECT id FROM pages WHERE id IN (SELECT id FROM sub) AND deleted_at IS NULL")
                .param(id).query(Long.class).list();
        if (ids.size() > 1 && !includeChildren) {
            throw new WikiException(409, "HAS_CHILDREN", "page has child pages", Map.of("childCount", ids.size() - 1));
        }
        String now = Instant.now().toString();
        for (long pid : ids) {
            jdbc.sql("UPDATE pages SET deleted_at = ?, deleted_by = ? WHERE id = ?").params(now, actorId, pid).update();
            jdbc.sql("UPDATE page_links SET target_page_id = NULL WHERE target_page_id = ?").param(pid).update();
        }
        audit.record(actorId, "PAGE_TRASH", "page", id, Map.of("path", row.path(), "pages", ids.size()));
        return ids.size();
    }

    /** 복원. 함께 휴지통에 들어간 하위 문서도 복원. 원래 경로가 점유됐으면 newSlug 필요 (409 PATH_EXISTS). */
    @Transactional
    public String restore(long id, String newSlug, long actorId) {
        Row row = load(id);
        if (row.deletedAt() == null) {
            throw new WikiException(409, "NOT_IN_TRASH", "page is not in trash");
        }
        String parentPath = WikiPaths.parentOf(row.path());
        if (row.parentId() != null && load(row.parentId()).deletedAt() != null) {
            throw new WikiException(409, "PARENT_TRASHED", "restore the parent page first", Map.of("parent", parentPath));
        }
        String slug = newSlug == null || newSlug.isBlank() ? row.slug() : newSlug.trim();
        if (!WikiPaths.isValidSlug(slug)) {
            throw WikiException.badRequest("invalid slug: " + slug);
        }
        String newPath = WikiPaths.join(parentPath, slug);
        if (pages.findActiveIdByPath(newPath).isPresent()) {
            throw new WikiException(409, "PATH_EXISTS", "path is occupied: " + newPath, Map.of("path", newPath));
        }
        Map<Long, String> group = new LinkedHashMap<>();
        jdbc.sql(SUBTREE + "SELECT id, path FROM pages WHERE id IN (SELECT id FROM sub) AND deleted_at = ?")
                .params(id, row.deletedAt())
                .query((rs, n) -> group.put(rs.getLong(1), newPath + rs.getString(2).substring(row.path().length())))
                .list();
        jdbc.sql("UPDATE pages SET slug = ? WHERE id = ?").params(slug, id).update();
        group.forEach((pid, path) -> {
            jdbc.sql("UPDATE pages SET path = ?, deleted_at = NULL, deleted_by = NULL WHERE id = ?").params(path, pid).update();
            jdbc.sql("UPDATE page_links SET target_page_id = ? WHERE target_path = ? AND target_page_id IS NULL")
                    .params(pid, path).update();
            links.rebuild(pid);
        });
        audit.record(actorId, "PAGE_RESTORE", "page", id, Map.of("path", newPath, "pages", group.size()));
        return newPath;
    }

    /** 휴지통 문서와 그 하위 문서 영구 삭제 (첨부 파일 포함). actorId가 null이면 자동 정리. */
    @Transactional
    public int purge(long id, Long actorId) {
        Row row = load(id);
        if (row.deletedAt() == null) {
            throw new WikiException(409, "NOT_IN_TRASH", "only pages in trash can be purged");
        }
        List<Long> ids = jdbc.sql(SUBTREE + "SELECT id FROM pages WHERE id IN (SELECT id FROM sub) ORDER BY length(path) DESC")
                .param(id).query(Long.class).list();
        for (long pid : ids) {
            jdbc.sql("DELETE FROM pages WHERE id = ?").param(pid).update();
            storage.deletePage(pid);
        }
        audit.record(actorId, "PAGE_PURGE", "page", id, Map.of("path", row.path(), "pages", ids.size()));
        return ids.size();
    }

    /** 보관 기간이 지난 휴지통 항목 영구 삭제. 삭제한 휴지통 항목 수 반환. */
    @Transactional
    public int purgeExpired(int retentionDays) {
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString();
        List<Long> roots = jdbc.sql("""
                SELECT p.id FROM pages p LEFT JOIN pages parent ON parent.id = p.parent_id
                WHERE p.deleted_at IS NOT NULL AND p.deleted_at < ? AND (parent.id IS NULL OR parent.deleted_at IS NULL)""")
                .param(cutoff).query(Long.class).list();
        roots.forEach(rootId -> purge(rootId, null));
        return roots.size();
    }

    /** 휴지통 목록: 함께 삭제된 묶음의 최상위 문서만. */
    public List<TrashItem> trashList(int retentionDays) {
        record Root(long id, String title, String path, String deletedAt, String deletedBy) {
        }
        List<Root> roots = jdbc.sql("""
                SELECT p.id, p.title, p.path, p.deleted_at, u.name FROM pages p
                LEFT JOIN pages parent ON parent.id = p.parent_id LEFT JOIN users u ON u.id = p.deleted_by
                WHERE p.deleted_at IS NOT NULL AND (parent.id IS NULL OR parent.deleted_at IS NULL OR parent.deleted_at <> p.deleted_at)
                ORDER BY p.deleted_at DESC""")
                .query((rs, n) -> new Root(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        List<TrashItem> items = new ArrayList<>();
        for (Root r : roots) {
            int count = jdbc.sql(SUBTREE + "SELECT count(*) FROM pages WHERE id IN (SELECT id FROM sub) AND deleted_at = ?")
                    .params(r.id(), r.deletedAt()).query(Integer.class).single();
            long bytes = jdbc.sql(SUBTREE + "SELECT coalesce(sum(size), 0) FROM attachments WHERE page_id IN (SELECT id FROM sub)")
                    .param(r.id()).query(Long.class).single();
            String purgeAt = Instant.parse(r.deletedAt()).plus(retentionDays, ChronoUnit.DAYS).toString();
            items.add(new TrashItem(r.id(), r.title(), r.path(), r.deletedAt(), r.deletedBy(), purgeAt, count, bytes));
        }
        return items;
    }

    /** 형제 사이 위치 지정. position이 null이면 그대로 둔다. */
    private void setPosition(Long parentId, long id, Integer position) {
        if (position == null) {
            return;
        }
        String where = parentId == null ? "parent_id IS NULL" : "parent_id = " + parentId;
        List<Long> siblings = new ArrayList<>(jdbc.sql("SELECT id FROM pages WHERE " + where
                        + " AND deleted_at IS NULL AND is_draft = 0 AND id <> ? ORDER BY sort_order, title")
                .param(id).query(Long.class).list());
        siblings.add(Math.clamp(position, 0, siblings.size()), id);
        for (int i = 0; i < siblings.size(); i++) {
            jdbc.sql("UPDATE pages SET sort_order = ? WHERE id = ?").params(i, siblings.get(i)).update();
        }
    }

    private Row load(long id) {
        Optional<Row> row = jdbc.sql("""
                SELECT id, parent_id, slug, path, title, content, version, deleted_at FROM pages
                WHERE id = ? AND is_draft = 0""")
                .param(id)
                .query((rs, n) -> {
                    long parent = rs.getLong(2);
                    Long parentId = rs.wasNull() ? null : parent;
                    return new Row(rs.getLong(1), parentId, rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7), rs.getString(8));
                }).optional();
        return row.orElseThrow(() -> WikiException.notFound("page " + id));
    }
}
