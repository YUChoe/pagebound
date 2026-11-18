package io.github.yuchoe.pagebound.core.page;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 문서 이력·특정 버전·두 버전 diff·역링크 (spec/features/F7-common.md 2절, MCP get_history / get_revision / get_backlinks).
 */
@Service
public class HistoryService {

    /** tokenName: MCP·API 토큰으로 쓴 리비전이면 토큰 이름 (에이전트 배지용). deltaBytes: 직전 버전 대비 UTF-8 바이트 증감. */
    public record Revision(int version, String title, String action, String source, String summary, String author,
                           String tokenName, String createdAt, int deltaBytes) {
    }

    public record RevisionContent(int version, String title, String path, String content, String createdAt) {
    }

    public record Backlink(String path, String title) {
    }

    /** side-by-side diff 한 줄. type: equal, delete, insert, change. 없는 쪽은 null. */
    public record DiffRow(String type, Integer oldLine, Integer newLine, String oldText, String newText) {
    }

    public record Diff(int from, int to, List<DiffRow> rows) {
    }

    private final JdbcClient jdbc;
    private final PageStore pages;

    public HistoryService(JdbcClient jdbc, PageStore pages) {
        this.jdbc = jdbc;
        this.pages = pages;
    }

    public List<Revision> history(String path, int limit) {
        return revisions(requireId(path), limit);
    }

    public List<Revision> revisions(long pageId, int limit) {
        requireActive(pageId);
        return jdbc.sql("""
                SELECT r.version, r.title, r.action, r.source, r.summary, u.name, t.name, r.created_at,
                       length(CAST(r.content AS BLOB)) - coalesce((SELECT length(CAST(o.content AS BLOB))
                           FROM page_revisions o WHERE o.page_id = r.page_id AND o.version = r.version - 1), 0)
                FROM page_revisions r LEFT JOIN users u ON u.id = r.author_id LEFT JOIN api_tokens t ON t.id = r.token_id
                WHERE r.page_id = ? ORDER BY r.version DESC LIMIT ?""")
                .params(pageId, limit)
                .query((rs, n) -> new Revision(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9)))
                .list();
    }

    public RevisionContent revision(String path, int version) {
        return revision(requireId(path), version);
    }

    public RevisionContent revision(long pageId, int version) {
        requireActive(pageId);
        return jdbc.sql("SELECT version, title, path, content, created_at FROM page_revisions WHERE page_id = ? AND version = ?")
                .params(pageId, version)
                .query((rs, n) -> new RevisionContent(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional()
                .orElseThrow(() -> WikiException.notFound("revision " + version));
    }

    /** from 버전 → to 버전 줄 단위 diff. from=0이면 빈 문서와 비교. */
    public Diff diff(long pageId, int from, int to) {
        List<String> before = from == 0 ? List.of() : BlameService.lines(revision(pageId, from).content());
        List<String> after = BlameService.lines(revision(pageId, to).content());
        return new Diff(from, to, rows(before, after));
    }

    static List<DiffRow> rows(List<String> before, List<String> after) {
        List<DiffRow> rows = new ArrayList<>();
        int oldIndex = 0;
        int newIndex = 0;
        for (AbstractDelta<String> delta : DiffUtils.diff(before, after).getDeltas()) {
            while (oldIndex < delta.getSource().getPosition()) {
                rows.add(new DiffRow("equal", oldIndex + 1, newIndex + 1, before.get(oldIndex), after.get(newIndex)));
                oldIndex++;
                newIndex++;
            }
            List<String> removed = delta.getSource().getLines();
            List<String> added = delta.getTarget().getLines();
            for (int i = 0; i < Math.max(removed.size(), added.size()); i++) {
                boolean hasOld = i < removed.size();
                boolean hasNew = i < added.size();
                String type = hasOld && hasNew ? "change" : hasOld ? "delete" : "insert";
                rows.add(new DiffRow(type, hasOld ? oldIndex + i + 1 : null, hasNew ? newIndex + i + 1 : null,
                        hasOld ? removed.get(i) : null, hasNew ? added.get(i) : null));
            }
            oldIndex += removed.size();
            newIndex += added.size();
        }
        while (oldIndex < before.size()) {
            rows.add(new DiffRow("equal", oldIndex + 1, newIndex + 1, before.get(oldIndex), after.get(newIndex)));
            oldIndex++;
            newIndex++;
        }
        return rows;
    }

    public List<Backlink> backlinks(String path) {
        return jdbc.sql("""
                SELECT p.path, p.title FROM page_links l JOIN pages p ON p.id = l.source_page_id
                WHERE l.target_page_id = ? AND p.deleted_at IS NULL AND p.is_draft = 0 ORDER BY p.path""")
                .param(requireId(path))
                .query((rs, n) -> new Backlink(rs.getString(1), rs.getString(2)))
                .list();
    }

    private long requireId(String path) {
        return pages.findActiveIdByPath(path).orElseThrow(() -> WikiException.notFound("page " + path));
    }

    private void requireActive(long pageId) {
        if (jdbc.sql("SELECT count(*) FROM pages WHERE id = ? AND deleted_at IS NULL AND is_draft = 0").param(pageId)
                .query(Integer.class).single() == 0) {
            throw WikiException.notFound("page " + pageId);
        }
    }
}
