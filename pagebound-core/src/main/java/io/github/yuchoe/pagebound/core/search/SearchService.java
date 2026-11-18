package io.github.yuchoe.pagebound.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;
import io.github.yuchoe.pagebound.core.page.PageReadService.Crumb;
import io.github.yuchoe.pagebound.core.page.WikiPaths;

/**
 * 검색 (spec/features/F11-search.md P0). 3자 이상은 FTS5 trigram, 1~2자는 LIKE.
 * 스니펫 강조는 \u0002(시작) \u0003(끝) 표식으로 전달한다 (클라이언트가 이스케이프 후 mark로 변환).
 */
@Service
public class SearchService {

    public static final char MARK_START = '\u0002';
    public static final char MARK_END = '\u0003';
    private static final int WINDOW = 60;

    public record Snippet(String text, boolean inCode) {
    }

    public record SectionRef(int index, String number, String title) {
    }

    /** files: 검색어가 들어간 첨부 파일명 ("첨부" 배지). */
    public record Hit(String path, String title, List<Crumb> breadcrumbs, List<String> tags, String updatedAt,
                      String updatedBy, List<Snippet> snippets, SectionRef section, List<String> files) {
    }

    public record Facet(String name, int count) {
    }

    public record Result(int total, int page, int size, List<Hit> items, List<Facet> tagFacets,
                         List<Facet> pathFacets) {
    }

    public record Suggestion(String path, String title, boolean exact) {
    }

    private record Row(long id, String title, String path, String updatedAt, double rank) {
    }

    private final JdbcClient jdbc;
    private final SearchIndexer indexer;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public SearchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.indexer = new SearchIndexer(jdbc);
    }

    /** extraTags·extraPath는 결과 화면 필터(URL 파라미터)로, 질의 안의 tag:·path:와 합쳐진다. */
    public Result search(String q, List<String> extraTags, String extraPath, String sort, int page, int size) {
        indexer.flush();
        SearchQuery query = SearchQuery.parse(q);
        List<String> tags = new ArrayList<>(query.tags());
        if (extraTags != null) {
            extraTags.stream().filter(t -> t != null && !t.isBlank()).forEach(tags::add);
        }
        String path = extraPath != null && !extraPath.isBlank() ? WikiPaths.normalize(extraPath) : query.path();
        int pageSize = Math.clamp(size, 1, 50);
        int pageNo = Math.max(1, page);
        if (query.needles().isEmpty() && tags.isEmpty() && path == null) {
            return new Result(0, pageNo, pageSize, List.of(), List.of(), List.of());
        }

        List<Row> rows = findRows(query, tags, path);
        Comparator<Row> byUpdated = Comparator.comparing(Row::updatedAt, Comparator.reverseOrder());
        if (!"updated".equals(sort) && !query.needles().isEmpty()) {
            String text = String.join(" ", query.needles()).toLowerCase(Locale.ROOT);
            rows.sort(Comparator.comparingInt((Row r) -> tier(r, text, query.needles()))
                    .thenComparingDouble(Row::rank).thenComparing(byUpdated));
        } else {
            rows.sort(byUpdated);
        }
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        List<Hit> items = rows.subList(from, Math.min(from + pageSize, rows.size())).stream()
                .map(r -> hit(r, query.needles(), query.scope())).toList();
        return new Result(rows.size(), pageNo, pageSize, items, tagFacets(rows), pathFacets(rows));
    }

    /** 검색 색인 재구축 (spec/features/F11-search.md 8절). 색인 대상 문서 수 반환. */
    public int rebuildIndex() {
        return indexer.rebuildAll();
    }

    /** 제목·경로 부분 일치 자동완성. 정확히 일치하면 exact=true로 맨 앞. */
    public List<Suggestion> suggest(String q, int limit) {
        String text = q == null ? "" : q.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        String like = like(text);
        String lower = text.toLowerCase(Locale.ROOT);
        String normalized = WikiPaths.normalize(text);
        return jdbc.sql("""
                SELECT path, title FROM pages WHERE deleted_at IS NULL AND is_draft = 0
                AND (title LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\') LIMIT 200""")
                .params(like, like)
                .query((rs, n) -> {
                    String path = rs.getString(1);
                    String title = rs.getString(2);
                    return new Suggestion(path, title, title.equalsIgnoreCase(text) || path.equals(normalized));
                }).list().stream()
                .sorted(Comparator.comparing((Suggestion s) -> !s.exact())
                        .thenComparing(s -> !s.title().toLowerCase(Locale.ROOT).startsWith(lower))
                        .thenComparingInt(s -> s.path().length()))
                .limit(Math.clamp(limit, 1, 20))
                .toList();
    }

    private List<Row> findRows(SearchQuery query, List<String> tags, String path) {
        StringBuilder sql = new StringBuilder("SELECT p.id, p.title, p.path, p.updated_at FROM pages p ");
        List<Object> params = new ArrayList<>();
        List<String> ftsNeedles = query.needles().stream().filter(SearchService::ftsEligible).toList();
        // 순위용 bm25는 FTS 질의 1회로 따로 구한다. LEFT JOIN 서브쿼리로 붙이면 SQLite가 행마다 MATCH를 다시 실행한다
        Map<Long, Double> ranks = new HashMap<>();
        if (!ftsNeedles.isEmpty()) {
            // 열 가중치: title, path, body, code, files
            jdbc.sql("SELECT rowid, bm25(search_index, 10.0, 5.0, 1.0, 1.0, 2.0) FROM search_index WHERE search_index MATCH ?")
                    .param(scoped(String.join(" OR ", ftsNeedles.stream().map(SearchService::ftsPhrase).toList()), query.scope()))
                    .query((rs, n) -> ranks.put(rs.getLong(1), rs.getDouble(2))).list();
        }
        sql.append("WHERE p.deleted_at IS NULL AND p.is_draft = 0");
        for (String needle : query.needles()) {
            if (ftsEligible(needle)) {
                sql.append(" AND p.id IN (SELECT rowid FROM search_index WHERE search_index MATCH ?)");
                params.add(scoped(ftsPhrase(needle), query.scope()));
            } else {
                // trigram은 3자 미만을 못 찾으므로 색인 열 LIKE (범위 지정 반영)
                List<String> columns = columns(query.scope());
                sql.append(" AND p.id IN (SELECT rowid FROM search_index WHERE ")
                        .append(String.join(" OR ", columns.stream().map(c -> c + " LIKE ? ESCAPE '\\'").toList()))
                        .append(')');
                columns.forEach(c -> params.add(like(needle)));
            }
        }
        for (String exclude : query.excludes()) {
            sql.append(" AND NOT (p.title LIKE ? ESCAPE '\\' OR p.content LIKE ? ESCAPE '\\')");
            String like = like(exclude);
            params.add(like);
            params.add(like);
        }
        for (String tag : tags) {
            sql.append(" AND EXISTS (SELECT 1 FROM page_tags pt JOIN tags t ON t.id = pt.tag_id")
                    .append(" WHERE pt.page_id = p.id AND t.name = ?)");
            params.add(tag.trim());
        }
        if (path != null && !path.isEmpty()) {
            sql.append(" AND (p.path = ? OR substr(p.path, 1, length(?) + 1) = ? || '/')");
            params.add(path);
            params.add(path);
            params.add(path);
        }
        return new ArrayList<>(jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        ranks.getOrDefault(rs.getLong(1), 0.0)))
                .list());
    }

    /** 범위 지정(in:)을 FTS5 열 필터로. */
    static String scoped(String expression, String scope) {
        return scope == null ? expression : column(scope) + " : (" + expression + ")";
    }

    private static String column(String scope) {
        return "file".equals(scope) ? "files" : scope;
    }

    private static List<String> columns(String scope) {
        return scope == null ? List.of("title", "path", "body", "code", "files") : List.of(column(scope));
    }

    /** 0 제목 완전 일치, 1 제목 접두 일치, 2 제목·경로 부분 일치, 3 본문만. */
    private static int tier(Row r, String text, List<String> needles) {
        String title = r.title().toLowerCase(Locale.ROOT);
        if (title.equals(text)) {
            return 0;
        }
        if (title.startsWith(text)) {
            return 1;
        }
        String path = r.path().toLowerCase(Locale.ROOT);
        for (String n : needles) {
            String needle = n.toLowerCase(Locale.ROOT);
            if (title.contains(needle) || path.contains(needle)) {
                return 2;
            }
        }
        return 3;
    }

    private Hit hit(Row r, List<String> needles, String scope) {
        record Detail(String content, String updatedBy) {
        }
        Detail d = jdbc.sql("SELECT p.content, u.name FROM pages p LEFT JOIN users u ON u.id = p.updated_by WHERE p.id = ?")
                .param(r.id()).query((rs, n) -> new Detail(rs.getString(1), rs.getString(2))).single();
        List<String> tags = jdbc.sql("""
                SELECT t.name FROM page_tags pt JOIN tags t ON t.id = pt.tag_id WHERE pt.page_id = ?
                ORDER BY t.name COLLATE NOCASE""").param(r.id()).query(String.class).list();
        List<Snippet> snippets = new ArrayList<>();
        SectionRef section = null;
        String content = d.content();
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> lowerNeedles = needles.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        List<MarkdownAnalyzer.Range> code = analyzer.codeRanges(content);
        int first = nextMatch(lower, lowerNeedles, 0)[0];
        // in:code면 코드 안의 첫 일치를 스니펫으로
        while ("code".equals(scope) && first >= 0 && !MarkdownAnalyzer.inAny(code, first)) {
            first = nextMatch(lower, lowerNeedles, first + 1)[0];
        }
        if (first >= 0) {
            int windowEnd = addSnippet(snippets, content, lower, lowerNeedles, first, code);
            int second = nextMatch(lower, lowerNeedles, windowEnd)[0];
            if (second >= 0) {
                addSnippet(snippets, content, lower, lowerNeedles, second, code);
            }
            for (MarkdownAnalyzer.Section s : analyzer.sections(content)) {
                if (s.start() <= first && first < s.end()) {
                    section = new SectionRef(s.index(), s.number(), s.title());
                }
            }
        } else if (!content.isBlank()) {
            String head = collapse(content.substring(0, Math.min(content.length(), WINDOW * 2)));
            snippets.add(new Snippet(head + (content.length() > WINDOW * 2 ? " …" : ""), false));
        }
        List<String> files = jdbc.sql("SELECT filename FROM attachments WHERE page_id = ? ORDER BY filename").param(r.id())
                .query(String.class).list().stream()
                .filter(name -> lowerNeedles.stream().anyMatch(n -> name.toLowerCase(Locale.ROOT).contains(n))).toList();
        return new Hit(r.path(), r.title(), breadcrumbs(r.path()), tags, r.updatedAt(), d.updatedBy(), snippets, section,
                files);
    }

    /** pos 주변 창을 스니펫으로 추가하고 창 끝 오프셋을 반환. */
    private static int addSnippet(List<Snippet> out, String content, String lower, List<String> needles, int pos,
                                  List<MarkdownAnalyzer.Range> code) {
        int start = Math.max(0, pos - WINDOW);
        int end = Math.min(content.length(), pos + WINDOW * 2);
        StringBuilder text = new StringBuilder(start > 0 ? "… " : "");
        int i = start;
        while (i < end) {
            int[] m = nextMatch(lower, needles, i);
            if (m[0] < 0 || m[0] >= end) {
                text.append(content, i, end);
                break;
            }
            int matchEnd = Math.min(m[0] + m[1], content.length());
            text.append(content, i, m[0]).append(MARK_START).append(content, m[0], matchEnd).append(MARK_END);
            i = matchEnd;
        }
        if (end < content.length()) {
            text.append(" …");
        }
        out.add(new Snippet(collapse(text.toString()), MarkdownAnalyzer.inAny(code, pos)));
        return end;
    }

    /** from 이후 가장 먼저 나오는 검색어 위치와 길이. 없으면 {-1, 0}. */
    private static int[] nextMatch(String lower, List<String> needles, int from) {
        int best = -1;
        int length = 0;
        for (String n : needles) {
            int i = lower.indexOf(n, from);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
                length = n.length();
            }
        }
        return new int[] {best, length};
    }

    private List<Crumb> breadcrumbs(String path) {
        List<String> prefixes = new ArrayList<>();
        for (String p = WikiPaths.parentOf(path); !p.isEmpty(); p = WikiPaths.parentOf(p)) {
            prefixes.addFirst(p);
        }
        if (prefixes.isEmpty()) {
            return List.of();
        }
        Map<String, String> titles = new HashMap<>();
        jdbc.sql("SELECT path, title FROM pages WHERE deleted_at IS NULL AND path IN ("
                        + String.join(",", prefixes.stream().map(x -> "?").toList()) + ")")
                .params(prefixes.toArray())
                .query((rs, n) -> titles.put(rs.getString(1), rs.getString(2))).list();
        return prefixes.stream().map(p -> new Crumb(p, titles.getOrDefault(p, WikiPaths.lastSegment(p)))).toList();
    }

    private List<Facet> tagFacets(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Object> ids = rows.stream().map(r -> (Object) r.id()).toList();
        return jdbc.sql("SELECT t.name, count(*) FROM page_tags pt JOIN tags t ON t.id = pt.tag_id WHERE pt.page_id IN ("
                        + String.join(",", ids.stream().map(x -> "?").toList())
                        + ") GROUP BY t.id ORDER BY 2 DESC, t.name COLLATE NOCASE LIMIT 10")
                .params(ids)
                .query((rs, n) -> new Facet(rs.getString(1), rs.getInt(2))).list();
    }

    private static List<Facet> pathFacets(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        rows.forEach(r -> counts.merge(r.path().split("/")[0], 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10).map(e -> new Facet(e.getKey(), e.getValue())).toList();
    }

    /** trigram은 3자 이상에서만 동작한다. */
    static boolean ftsEligible(String needle) {
        return needle.codePointCount(0, needle.length()) >= 3;
    }

    /** FTS 연산자(-, :, /, ., *)로 해석되지 않도록 항상 구문으로 감싼다. */
    static String ftsPhrase(String needle) {
        return "\"" + needle.replace("\"", "\"\"") + "\"";
    }

    static String like(String needle) {
        return "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** trim()은 U+0020 이하 제어 문자(강조 표식 포함)까지 지우므로 strip() 사용. */
    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
