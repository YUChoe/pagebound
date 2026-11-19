package io.github.yuchoe.pagebound.core.admin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;

/**
 * 관리자 조회 (spec/features/F12-admin.md 4절 대시보드, 7절 정리 대상, 12절 감사 로그).
 */
@Service
public class AdminQueries {

    public record AuditEntry(long id, String actor, String action, String targetType, String targetId, String detail,
                             String createdAt) {
    }

    public record AuditPage(int total, int page, int size, List<AuditEntry> items) {
    }

    public record Unreferenced(long pageId, String path, long attachmentId, String filename, long size) {
    }

    public record RedLink(String target, int references, List<String> sources) {
    }

    public record PageRef(long id, String path, String title, String at) {
    }

    private static final String ACTIVE = "deleted_at IS NULL AND is_draft = 0";

    private final JdbcClient jdbc;

    public AdminQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> dashboard(int retentionDays) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("pages", Map.of(
                "active", count("SELECT count(*) FROM pages WHERE " + ACTIVE),
                "trash", count("SELECT count(*) FROM pages WHERE deleted_at IS NOT NULL"),
                "drafts", count("SELECT count(*) FROM pages WHERE is_draft = 1 AND deleted_at IS NULL")));
        d.put("attachments", Map.of(
                "count", count("SELECT count(*) FROM attachments"),
                "bytes", jdbc.sql("SELECT coalesce(sum(size), 0) FROM attachments").query(Long.class).single()));
        Map<String, Integer> roles = new LinkedHashMap<>();
        jdbc.sql("SELECT role, count(*) FROM users WHERE is_active = 1 GROUP BY role")
                .query(rs -> {
                    roles.put(rs.getString(1), rs.getInt(2));
                });
        d.put("users", Map.of("byRole", roles, "inactive", count("SELECT count(*) FROM users WHERE is_active = 0"),
                "activeAdmins", roles.getOrDefault("ADMIN", 0)));
        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        Map<String, Integer> edits = new LinkedHashMap<>();
        jdbc.sql("SELECT source, count(*) FROM page_revisions WHERE created_at >= ? GROUP BY source").param(weekAgo)
                .query(rs -> {
                    edits.put(rs.getString(1), rs.getInt(2));
                });
        d.put("editsLast7Days", edits);
        String purgeCutoff = Instant.now().minus(retentionDays - 7L, ChronoUnit.DAYS).toString();
        d.put("cleanup", Map.of(
                "unreferencedAttachments", unreferencedAttachments().size(),
                "redLinks", count("""
                        SELECT count(DISTINCT l.target_path) FROM page_links l JOIN pages p ON p.id = l.source_page_id
                        WHERE l.target_page_id IS NULL AND p.deleted_at IS NULL AND p.is_draft = 0"""),
                "purgeWithin7Days", jdbc.sql("""
                        SELECT count(*) FROM pages WHERE deleted_at IS NOT NULL AND deleted_at < ?""")
                        .param(purgeCutoff).query(Integer.class).single()));
        d.put("recentAudit", audit(null, null, null, null, 1, 5).items());
        return d;
    }

    /** 기간(ISO 시각)·행위자·작업 종류 필터, page는 1부터. */
    public AuditPage audit(String from, String to, Long actorId, String action, int page, int size) {
        String where = """
                WHERE (:from IS NULL OR a.created_at >= :from) AND (:to IS NULL OR a.created_at < :to)
                  AND (:actor IS NULL OR a.actor_id = :actor) AND (:action IS NULL OR a.action = :action)""";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", blankToNull(from));
        params.put("to", blankToNull(to));
        params.put("actor", actorId);
        params.put("action", blankToNull(action));
        int total = jdbc.sql("SELECT count(*) FROM audit_log a " + where).params(params).query(Integer.class).single();
        params.put("limit", size);
        params.put("offset", (Math.max(page, 1) - 1) * size);
        List<AuditEntry> items = jdbc.sql("""
                SELECT a.id, u.name, a.action, a.target_type, a.target_id, a.detail, a.created_at
                FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id %s
                ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset""".formatted(where))
                .params(params)
                .query((rs, n) -> new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();
        return new AuditPage(total, Math.max(page, 1), size, items);
    }

    /** 본문에서 URL로 참조하지 않는 첨부 (활성 문서, 용량순). */
    public List<Unreferenced> unreferencedAttachments() {
        record Row(long pageId, String path, String content, long id, String filename, long size) {
        }
        List<Unreferenced> out = new ArrayList<>();
        jdbc.sql("""
                SELECT p.id, p.path, p.content, a.id, a.filename, a.size FROM attachments a JOIN pages p ON p.id = a.page_id
                WHERE p.deleted_at IS NULL AND p.is_draft = 0 ORDER BY a.size DESC""")
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getLong(6)))
                .list().forEach(r -> {
                    if (!r.content().contains(AttachmentStorage.url(r.pageId(), r.filename()))) {
                        out.add(new Unreferenced(r.pageId(), r.path(), r.id(), r.filename(), r.size()));
                    }
                });
        return out;
    }

    public List<RedLink> redLinks() {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT l.target_path, p.path FROM page_links l JOIN pages p ON p.id = l.source_page_id
                WHERE l.target_page_id IS NULL AND p.deleted_at IS NULL AND p.is_draft = 0 ORDER BY l.target_path, p.path""")
                .query(rs -> {
                    byTarget.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return byTarget.entrySet().stream()
                .map(e -> new RedLink(e.getKey(), e.getValue().size(), e.getValue()))
                .sorted((a, b) -> b.references() - a.references())
                .toList();
    }

    /** 사용자별 최근 편집 (F12 5절 P1). trashed: 지금은 휴지통에 있는 문서. */
    public record UserEdit(String path, String title, int version, String action, String source, String summary,
                           String createdAt, boolean trashed) {
    }

    public List<UserEdit> recentEdits(long userId, int limit) {
        return jdbc.sql("""
                SELECT p.path, r.title, r.version, r.action, r.source, r.summary, r.created_at, p.deleted_at IS NOT NULL
                FROM page_revisions r JOIN pages p ON p.id = r.page_id
                WHERE r.author_id = ? AND p.is_draft = 0 ORDER BY r.created_at DESC, r.id DESC LIMIT ?""")
                .params(userId, limit)
                .query((rs, n) -> new UserEdit(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)))
                .list();
    }

    /** 본문도 하위 문서도 없는 활성 문서. */
    public List<PageRef> emptyPages() {
        return jdbc.sql("""
                SELECT p.id, p.path, p.title, p.updated_at FROM pages p
                WHERE p.%s AND trim(p.content) = ''
                  AND NOT EXISTS (SELECT 1 FROM pages c WHERE c.parent_id = p.id AND c.deleted_at IS NULL)
                ORDER BY p.path""".formatted(ACTIVE))
                .query((rs, n) -> new PageRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    public List<PageRef> drafts() {
        return jdbc.sql("""
                SELECT p.id, p.path, p.title, p.created_at FROM pages p
                WHERE p.is_draft = 1 AND p.deleted_at IS NULL ORDER BY p.created_at""")
                .query((rs, n) -> new PageRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
