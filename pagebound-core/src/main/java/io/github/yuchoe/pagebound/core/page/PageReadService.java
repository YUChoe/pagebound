package io.github.yuchoe.pagebound.core.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;

/**
 * 문서 조회 (웹 UI, REST, MCP 공용). 휴지통·초안 문서는 제외한다.
 */
@Service
public class PageReadService {

    private static final String ACTIVE = "deleted_at IS NULL AND is_draft = 0";

    public record TreeNode(long id, String title, String path, int childCount, List<TreeNode> children) {
    }

    public record Crumb(String path, String title) {
    }

    /** referenced: 본문에 이 첨부 URL이 있는지 (spec/features/F2-attachments.md 3절 "본문 미참조" 배지). */
    public record AttachmentInfo(String filename, String mime, long size, String url, boolean referenced) {
    }

    /** 위키 링크 대상 해석 결과. exists=false면 레드 링크, path는 alias 해석 후 현재 경로. */
    public record LinkTarget(boolean exists, String path) {
    }

    public record PageView(long id, String path, String title, String content, int version, String createdAt,
                           String updatedAt, String updatedBy, List<Crumb> breadcrumbs, List<String> tags,
                           Map<String, LinkTarget> links, List<AttachmentInfo> attachments, List<Crumb> children) {
    }

    public record RecentChange(String path, String title, int version, String action, String source,
                               String summary, String author, String createdAt, int deltaBytes, String tokenName) {
    }

    public record AttachmentFile(long pageId, long attachmentId, String filename, String mime, long size,
                                 String sha256) {
    }

    private final JdbcClient jdbc;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public PageReadService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** parentId가 null이면 최상위. depth만큼 하위를 펼친다 (1 = 직계만). */
    public List<TreeNode> tree(Long parentId, int depth) {
        String where = parentId == null ? "p.parent_id IS NULL" : "p.parent_id = :parent";
        record Row(long id, String title, String path, int childCount) {
        }
        var spec = jdbc.sql("""
                SELECT p.id, p.title, p.path,
                       (SELECT count(*) FROM pages c WHERE c.parent_id = p.id AND c.%s) AS child_count
                FROM pages p WHERE %s AND p.%s ORDER BY p.sort_order, p.title""".formatted(ACTIVE, where, ACTIVE));
        if (parentId != null) {
            spec = spec.param("parent", parentId);
        }
        List<Row> rows = spec.query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                .list();
        List<TreeNode> nodes = new ArrayList<>();
        for (Row r : rows) {
            List<TreeNode> children = depth > 1 && r.childCount() > 0 ? tree(r.id(), depth - 1) : List.of();
            nodes.add(new TreeNode(r.id(), r.title(), r.path(), r.childCount(), children));
        }
        return nodes;
    }

    public Optional<PageView> view(String path) {
        record Row(long id, Long parentId, String path, String title, String content, int version, String createdAt,
                   String updatedAt, String updatedBy) {
        }
        Optional<Row> found = jdbc.sql("""
                SELECT p.id, p.parent_id, p.path, p.title, p.content, p.version, p.created_at, p.updated_at, u.name
                FROM pages p LEFT JOIN users u ON u.id = p.updated_by
                WHERE p.path = ? AND p.%s""".formatted(ACTIVE))
                .param(path)
                .query((rs, n) -> {
                    long parent = rs.getLong(2);
                    Long parentId = rs.wasNull() ? null : parent;
                    return new Row(rs.getLong(1), parentId, rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9));
                })
                .optional();
        return found.map(r -> new PageView(r.id(), r.path(), r.title(), r.content(), r.version(), r.createdAt(),
                r.updatedAt(), r.updatedBy(), breadcrumbs(r.parentId()), tags(r.id()), links(r.id()),
                attachments(r.id(), r.content()), children(r.id())));
    }

    /** 이전 경로(alias)가 가리키는 문서의 현재 경로. */
    public Optional<String> aliasTarget(String path) {
        return jdbc.sql("""
                SELECT p.path FROM page_aliases a JOIN pages p ON p.id = a.page_id
                WHERE a.path = ? AND p.%s""".formatted(ACTIVE))
                .param(path).query(String.class).optional();
    }

    public boolean exists(String path) {
        return jdbc.sql("SELECT count(*) FROM pages WHERE path = ? AND " + ACTIVE).param(path)
                .query(Integer.class).single() > 0;
    }

    public Optional<String> pathById(long id) {
        return jdbc.sql("SELECT path FROM pages WHERE id = ? AND " + ACTIVE).param(id).query(String.class).optional();
    }

    public Optional<String> content(String path) {
        return jdbc.sql("SELECT content FROM pages WHERE path = ? AND " + ACTIVE).param(path)
                .query(String.class).optional();
    }

    public Optional<List<MarkdownAnalyzer.Section>> outline(String path) {
        return content(path).map(analyzer::sections);
    }

    /** 최근 변경 (리비전 기준). deltaBytes = 직전 버전 대비 UTF-8 바이트 증감. */
    public List<RecentChange> recent(int limit) {
        return recent(null, limit);
    }

    /** since(ISO-8601)가 있으면 그 이후 변경만. */
    public List<RecentChange> recent(String since, int limit) {
        return jdbc.sql("""
                SELECT p.path, r.title, r.version, r.action, r.source, r.summary, u.name, r.created_at,
                       length(CAST(r.content AS BLOB)) - coalesce((SELECT length(CAST(o.content AS BLOB))
                           FROM page_revisions o WHERE o.page_id = r.page_id AND o.version = r.version - 1), 0),
                       t.name
                FROM page_revisions r JOIN pages p ON p.id = r.page_id LEFT JOIN users u ON u.id = r.author_id
                LEFT JOIN api_tokens t ON t.id = r.token_id
                WHERE p.%s AND (? IS NULL OR r.created_at >= ?)
                ORDER BY r.created_at DESC, r.id DESC LIMIT ?""".formatted(ACTIVE))
                .params(since, since, limit)
                .query((rs, n) -> new RecentChange(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                        rs.getString(10)))
                .list();
    }

    public Optional<AttachmentFile> attachment(long pageId, String filename) {
        return jdbc.sql("""
                SELECT a.page_id, a.id, a.filename, a.mime, a.size, a.sha256
                FROM attachments a JOIN pages p ON p.id = a.page_id
                WHERE a.page_id = ? AND a.filename = ? AND p.deleted_at IS NULL""")
                .params(pageId, filename)
                .query((rs, n) -> new AttachmentFile(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6)))
                .optional();
    }

    private List<Crumb> breadcrumbs(Long parentId) {
        List<Crumb> crumbs = new ArrayList<>();
        Long current = parentId;
        while (current != null) {
            record Row(String path, String title, Long parentId) {
            }
            Row row = jdbc.sql("SELECT path, title, parent_id FROM pages WHERE id = ?").param(current)
                    .query((rs, n) -> {
                        String p = rs.getString(1);
                        String t = rs.getString(2);
                        long parent = rs.getLong(3);
                        return new Row(p, t, rs.wasNull() ? null : parent);
                    }).single();
            crumbs.addFirst(new Crumb(row.path(), row.title()));
            current = row.parentId();
        }
        return crumbs;
    }

    private List<String> tags(long pageId) {
        return jdbc.sql("""
                SELECT t.name FROM page_tags pt JOIN tags t ON t.id = pt.tag_id
                WHERE pt.page_id = ? ORDER BY t.name COLLATE NOCASE""")
                .param(pageId).query(String.class).list();
    }

    private Map<String, LinkTarget> links(long pageId) {
        Map<String, LinkTarget> links = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT l.target_path, t.path FROM page_links l
                LEFT JOIN pages t ON t.id = l.target_page_id AND t.deleted_at IS NULL AND t.is_draft = 0
                WHERE l.source_page_id = ? ORDER BY l.target_path""")
                .param(pageId)
                .query((rs, n) -> {
                    String current = rs.getString(2);
                    links.put(rs.getString(1), new LinkTarget(current != null, current == null ? rs.getString(1) : current));
                    return null;
                }).list();
        return links;
    }

    private List<AttachmentInfo> attachments(long pageId, String content) {
        return jdbc.sql("SELECT filename, mime, size FROM attachments WHERE page_id = ? ORDER BY filename")
                .param(pageId)
                .query((rs, n) -> {
                    String url = AttachmentStorage.url(pageId, rs.getString(1));
                    return new AttachmentInfo(rs.getString(1), rs.getString(2), rs.getLong(3), url, content.contains(url));
                })
                .list();
    }

    private List<Crumb> children(long pageId) {
        return jdbc.sql("SELECT path, title FROM pages WHERE parent_id = ? AND %s ORDER BY sort_order, title"
                        .formatted(ACTIVE))
                .param(pageId).query((rs, n) -> new Crumb(rs.getString(1), rs.getString(2))).list();
    }
}
