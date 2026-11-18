package io.github.yuchoe.pagebound.core.search;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;

/**
 * search_index 갱신 (spec/features/F11-search.md 8절). 트리거가 쌓은 search_queue의 문서만 다시 색인한다.
 * 본문(body)은 코드블록을 뺀 마크다운, code는 코드블록 내용, files는 첨부 파일명.
 */
final class SearchIndexer {

    private final JdbcClient jdbc;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    SearchIndexer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 큐 처리. 큐에서 먼저 지운 뒤 색인하므로 그 사이의 변경은 다시 큐에 쌓인다. */
    synchronized int flush() {
        List<Long> ids = jdbc.sql("SELECT page_id FROM search_queue").query(Long.class).list();
        for (long id : ids) {
            jdbc.sql("DELETE FROM search_queue WHERE page_id = ?").param(id).update();
            index(id);
        }
        return ids.size();
    }

    synchronized int rebuildAll() {
        jdbc.sql("DELETE FROM search_index").update();
        jdbc.sql("INSERT OR IGNORE INTO search_queue (page_id) SELECT id FROM pages").update();
        flush();
        return jdbc.sql("SELECT count(*) FROM search_index").query(Integer.class).single();
    }

    private void index(long id) {
        jdbc.sql("DELETE FROM search_index WHERE rowid = ?").param(id).update();
        record Page(String title, String path, String content) {
        }
        jdbc.sql("SELECT title, path, content FROM pages WHERE id = ? AND deleted_at IS NULL AND is_draft = 0")
                .param(id).query((rs, n) -> new Page(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().ifPresent(p -> {
                    StringBuilder body = new StringBuilder();
                    StringBuilder code = new StringBuilder();
                    int at = 0;
                    for (MarkdownAnalyzer.Range r : analyzer.codeRanges(p.content())) {
                        if (r.start() < at) {
                            continue;
                        }
                        body.append(p.content(), at, r.start()).append('\n');
                        code.append(p.content(), r.start(), r.end()).append('\n');
                        at = r.end();
                    }
                    body.append(p.content().substring(at));
                    String files = String.join("\n", jdbc.sql("SELECT filename FROM attachments WHERE page_id = ?")
                            .param(id).query(String.class).list());
                    jdbc.sql("INSERT INTO search_index (rowid, title, path, body, code, files) VALUES (?, ?, ?, ?, ?, ?)")
                            .params(id, p.title(), p.path(), body.toString(), code.toString(), files).update();
                });
    }
}
