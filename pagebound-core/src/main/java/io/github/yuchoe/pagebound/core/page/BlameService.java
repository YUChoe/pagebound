package io.github.yuchoe.pagebound.core.page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 행별 blame (spec/features/F13-blame.md). 리비전을 버전 순으로 줄 diff 하여 줄마다 마지막으로 바꾼 버전을 구한다.
 * 결과는 page_blame에 캐시하고, 새 리비전이 생기면 캐시 이후 리비전만 이어서 계산한다.
 */
@Service
public class BlameService {

    /** start는 1부터 시작하는 줄 번호. */
    public record Hunk(int start, int count, int version) {
    }

    public record RevisionInfo(String author, String createdAt, String source, String action, String summary,
                               String tokenName) {
    }

    public record Blame(long pageId, int version, String content, List<Hunk> hunks,
                        Map<Integer, RevisionInfo> revisions) {
    }

    private record Page(String content, int version) {
    }

    private record Cache(int version, List<Integer> lines) {
    }

    private final JdbcClient jdbc;

    public BlameService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Blame blame(long pageId) {
        Page page = jdbc.sql("SELECT content, version FROM pages WHERE id = ? AND deleted_at IS NULL AND is_draft = 0")
                .param(pageId).query((rs, n) -> new Page(rs.getString(1), rs.getInt(2))).optional()
                .orElseThrow(() -> WikiException.notFound("page " + pageId));
        List<String> current = lines(page.content());

        Cache cache = jdbc.sql("SELECT version, lines FROM page_blame WHERE page_id = ?").param(pageId)
                .query((rs, n) -> new Cache(rs.getInt(1), decode(rs.getString(2)))).optional().orElse(null);
        List<Integer> attribution;
        if (cache != null && cache.version() == page.version() && cache.lines().size() == current.size()) {
            attribution = cache.lines();
        } else {
            attribution = compute(pageId, cache, page, current);
            jdbc.sql("INSERT OR REPLACE INTO page_blame (page_id, version, lines) VALUES (?, ?, ?)")
                    .params(pageId, page.version(), encode(attribution)).update();
        }
        return new Blame(pageId, page.version(), page.content(), hunks(attribution), revisions(pageId, attribution));
    }

    /** 캐시가 유효하면 그 이후 리비전만, 아니면 처음부터 계산. */
    private List<Integer> compute(long pageId, Cache cache, Page page, List<String> current) {
        int base = 0;
        List<String> previous = List.of();
        List<Integer> attribution = List.of();
        if (cache != null && cache.version() < page.version()) {
            Optional<String> baseContent = revisionContent(pageId, cache.version());
            if (baseContent.isPresent() && lines(baseContent.get()).size() == cache.lines().size()) {
                base = cache.version();
                previous = lines(baseContent.get());
                attribution = cache.lines();
            }
        }
        record Rev(int version, String content, String action) {
        }
        List<Rev> revs = jdbc.sql("SELECT version, content, action FROM page_revisions WHERE page_id = ? AND version > ? ORDER BY version")
                .params(pageId, base).query((rs, n) -> new Rev(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
        Integer lastMigration = jdbc.sql("SELECT max(version) FROM page_revisions WHERE page_id = ? AND source = 'migration'")
                .param(pageId).query((rs, n) -> (Integer) rs.getObject(1)).list().getFirst();
        for (Rev rev : revs) {
            List<String> next = lines(rev.content());
            // 시스템 변경 무시 (F13 P1): 링크 자동 갱신, 이전 시점 본문 변환(마지막 migration 리비전)
            boolean transparent = "link-updated".equals(rev.action())
                    || rev.version() > 1 && Integer.valueOf(rev.version()).equals(lastMigration);
            attribution = apply(attribution, previous, next, rev.version(), transparent);
            previous = next;
        }
        // 마지막 리비전과 현재 본문이 다르면(리비전 없는 수정) 현재 버전으로 귀속
        if (!previous.equals(current)) {
            attribution = apply(attribution, previous, current, page.version(), false);
        }
        return attribution;
    }

    /**
     * previous→next 줄 diff: 변경 없는 줄은 기존 귀속 유지, 추가·변경 줄은 version.
     * transparent면 줄 수가 같은 변경 묶음은 기존 귀속을 유지한다 (시스템 변경 무시).
     */
    static List<Integer> apply(List<Integer> attribution, List<String> previous, List<String> next, int version,
                               boolean transparent) {
        List<Integer> out = new ArrayList<>(next.size());
        int source = 0;
        for (AbstractDelta<String> delta : DiffUtils.diff(previous, next).getDeltas()) {
            int position = delta.getSource().getPosition();
            while (source < position) {
                out.add(attribution.get(source++));
            }
            boolean keep = transparent && delta.getSource().size() == delta.getTarget().size();
            for (int i = 0; i < delta.getTarget().size(); i++) {
                out.add(keep ? attribution.get(position + i) : version);
            }
            source = position + delta.getSource().size();
        }
        while (source < previous.size()) {
            out.add(attribution.get(source++));
        }
        return out;
    }

    static List<String> lines(String content) {
        return Arrays.asList(content.split("\n", -1));
    }

    /** 런렝스 "버전*줄수,버전*줄수". */
    static String encode(List<Integer> attribution) {
        StringBuilder out = new StringBuilder();
        for (Hunk h : hunks(attribution)) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(h.version()).append('*').append(h.count());
        }
        return out.toString();
    }

    static List<Integer> decode(String encoded) {
        List<Integer> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String part : encoded.split(",")) {
            int star = part.indexOf('*');
            int version = Integer.parseInt(part.substring(0, star));
            int count = Integer.parseInt(part.substring(star + 1));
            for (int i = 0; i < count; i++) {
                out.add(version);
            }
        }
        return out;
    }

    static List<Hunk> hunks(List<Integer> attribution) {
        List<Hunk> hunks = new ArrayList<>();
        int i = 0;
        while (i < attribution.size()) {
            int version = attribution.get(i);
            int start = i;
            while (i < attribution.size() && attribution.get(i) == version) {
                i++;
            }
            hunks.add(new Hunk(start + 1, i - start, version));
        }
        return hunks;
    }

    private Optional<String> revisionContent(long pageId, int version) {
        return jdbc.sql("SELECT content FROM page_revisions WHERE page_id = ? AND version = ?")
                .params(pageId, version).query(String.class).optional();
    }

    private Map<Integer, RevisionInfo> revisions(long pageId, List<Integer> attribution) {
        Map<Integer, RevisionInfo> out = new LinkedHashMap<>();
        for (int version : new TreeSet<>(attribution)) {
            jdbc.sql("""
                    SELECT u.name, r.created_at, r.source, r.action, r.summary, t.name FROM page_revisions r
                    LEFT JOIN users u ON u.id = r.author_id LEFT JOIN api_tokens t ON t.id = r.token_id
                    WHERE r.page_id = ? AND r.version = ?""")
                    .params(pageId, version)
                    .query((rs, n) -> new RevisionInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)))
                    .optional().ifPresent(info -> out.put(version, info));
        }
        return out;
    }
}
