package io.github.yuchoe.pagebound.core.page;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 문서 이력·특정 버전·역링크 조회 (MCP get_history / get_revision / get_backlinks, 역사 화면 공용).
 */
@Service
public class HistoryService {

    /** tokenName: MCP·API 토큰으로 쓴 리비전이면 토큰 이름 (에이전트 배지용). */
    public record Revision(int version, String title, String action, String source, String summary, String author,
                           String tokenName, String createdAt) {
    }

    public record RevisionContent(int version, String title, String path, String content, String createdAt) {
    }

    public record Backlink(String path, String title) {
    }

    private final JdbcClient jdbc;
    private final PageStore pages;

    public HistoryService(JdbcClient jdbc, PageStore pages) {
        this.jdbc = jdbc;
        this.pages = pages;
    }

    public List<Revision> history(String path, int limit) {
        return jdbc.sql("""
                SELECT r.version, r.title, r.action, r.source, r.summary, u.name, t.name, r.created_at
                FROM page_revisions r LEFT JOIN users u ON u.id = r.author_id LEFT JOIN api_tokens t ON t.id = r.token_id
                WHERE r.page_id = ? ORDER BY r.version DESC LIMIT ?""")
                .params(requireId(path), limit)
                .query((rs, n) -> new Revision(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .list();
    }

    public RevisionContent revision(String path, int version) {
        return jdbc.sql("SELECT version, title, path, content, created_at FROM page_revisions WHERE page_id = ? AND version = ?")
                .params(requireId(path), version)
                .query((rs, n) -> new RevisionContent(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional()
                .orElseThrow(() -> WikiException.notFound("revision " + version));
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
}
