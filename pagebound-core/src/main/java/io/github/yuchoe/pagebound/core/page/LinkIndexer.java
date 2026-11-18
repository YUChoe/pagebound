package io.github.yuchoe.pagebound.core.page;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;

/**
 * page_links 재계산. 해석 순서: 활성 문서 경로 → alias → 미해결(레드 링크, target_page_id NULL).
 */
@Component
public class LinkIndexer {

    private final JdbcClient jdbc;
    private final PageStore pages;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public LinkIndexer(JdbcClient jdbc, PageStore pages) {
        this.jdbc = jdbc;
        this.pages = pages;
    }

    public void rebuild(long pageId) {
        record Row(String path, String content) {
        }
        Row row = jdbc.sql("SELECT path, content FROM pages WHERE id = ?").param(pageId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2))).single();

        Set<String> targets = new LinkedHashSet<>();
        for (MarkdownAnalyzer.WikiLink link : analyzer.wikiLinks(row.content(), row.path())) {
            if (!link.path().isEmpty()) {
                targets.add(link.path());
            }
        }
        jdbc.sql("DELETE FROM page_links WHERE source_page_id = ?").param(pageId).update();
        for (String target : targets) {
            Long targetId = pages.findActiveIdByPath(target)
                    .or(() -> pages.findIdByAlias(target))
                    .orElse(null);
            jdbc.sql("INSERT INTO page_links (source_page_id, target_path, target_page_id) VALUES (?, ?, ?)")
                    .params(pageId, target, targetId)
                    .update();
        }
    }

    public void rebuildAll() {
        List<Long> ids = jdbc.sql("SELECT id FROM pages WHERE deleted_at IS NULL").query(Long.class).list();
        ids.forEach(this::rebuild);
    }
}
