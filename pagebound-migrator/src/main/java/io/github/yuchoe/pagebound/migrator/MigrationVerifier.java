package io.github.yuchoe.pagebound.migrator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import io.github.yuchoe.pagebound.core.tag.TagNames;
import io.github.yuchoe.pagebound.migrator.MigrationReport.Check;

/**
 * 마이그레이션 결과 자동 검증 (spec/features/F8-migration.md 6절 중 M1 범위).
 */
final class MigrationVerifier {

    private static final Pattern FILE_URL = Pattern.compile("/p/(\\d+)/files/([^)\\s\"'>]+)");
    private static final Pattern COLLAPSED_FENCE = Pattern.compile("(?m)^(`{3,}|~{3,})[^\\n]*\\bcollapsed\\b");
    private static final Pattern DIAGRAM_FENCE = Pattern.compile("(?m)^`{3,}diagram[ \\t]*$");

    private final JdbcClient jdbc;
    private final AttachmentStorage storage;

    MigrationVerifier(JdbcClient jdbc, AttachmentStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    List<Check> verify(WikiJsSource src, MigrationReport report) {
        List<WikiJsSource.Page> srcPages = src.pages();
        List<Check> checks = new ArrayList<>();
        List<String> contents = jdbc.sql("SELECT content FROM pages ORDER BY id").query(String.class).list();

        Set<String> paths = srcPages.stream().map(WikiJsSource.Page::path).collect(Collectors.toSet());
        Set<String> virtual = new HashSet<>();
        paths.forEach(p -> {
            for (String a = WikiPaths.parentOf(p); !a.isEmpty(); a = WikiPaths.parentOf(a)) {
                if (!paths.contains(a)) {
                    virtual.add(a);
                }
            }
        });
        int orphanPage = jdbc.sql("SELECT count(*) FROM pages WHERE path = ?").param(Migrator.ORPHAN_SLUG)
                .query(Integer.class).single();
        int expectedPages = srcPages.size() + virtual.size() + orphanPage;
        int pageCount = count("SELECT count(*) FROM pages");
        checks.add(new Check("문서 수", srcPages.size() + " + 가상 폴더 " + virtual.size() + " + 미분류 " + orphanPage
                + " = " + expectedPages, String.valueOf(pageCount), pageCount == expectedPages));

        int aliasOk = 0;
        for (WikiJsSource.Page p : srcPages) {
            List<String> target = jdbc.sql(
                    "SELECT p.path FROM page_aliases a JOIN pages p ON p.id = a.page_id WHERE a.path = ?")
                    .param(p.path()).query(String.class).list();
            aliasOk += target.equals(List.of(p.path())) ? 1 : 0;
        }
        checks.add(ratio("원본 path → alias 조회", aliasOk, srcPages.size()));

        int badPaths = count("""
                SELECT count(*) FROM pages c LEFT JOIN pages p ON p.id = c.parent_id
                WHERE c.path <> CASE WHEN p.id IS NULL THEN c.slug ELSE p.path || '/' || c.slug END""");
        checks.add(new Check("path = 부모 path + slug", "불일치 0", "불일치 " + badPaths, badPaths == 0));

        checks.add(verifyAttachmentHashes(src, report));
        checks.add(verifyFileReferences(contents));
        checks.add(verifyTags(src));

        int expectedRevisions = src.history().size() + srcPages.size() + virtual.size() + orphanPage;
        int revisions = count("SELECT count(*) FROM page_revisions");
        checks.add(new Check("리비전 수", "이력 " + src.history().size() + " + 현재 " + srcPages.size()
                + " + 가상/미분류 " + (virtual.size() + orphanPage) + " = " + expectedRevisions,
                String.valueOf(revisions), revisions == expectedRevisions));

        int srcDetails = srcPages.stream().mapToInt(p -> occurrences(p.content(), "<details>")).sum();
        int details = contents.stream().mapToInt(c -> occurrences(c, "<details>")).sum();
        int collapsed = contents.stream().mapToInt(c -> matches(COLLAPSED_FENCE, c)).sum();
        checks.add(new Check("<details> 잔존 / collapsed 코드블록", "0 / " + srcDetails, details + " / " + collapsed,
                details == 0 && collapsed == srcDetails));

        int srcDiagrams = srcPages.stream().mapToInt(p -> matches(DIAGRAM_FENCE, p.content())).sum();
        int diagrams = contents.stream().mapToInt(c -> matches(DIAGRAM_FENCE, c)).sum();
        int svg = count("SELECT count(*) FROM attachments WHERE filename LIKE 'diagram-%.svg'");
        checks.add(new Check("```diagram 잔존 / diagram SVG 첨부", "0 / " + srcDiagrams, diagrams + " / " + svg,
                diagrams == 0 && svg == srcDiagrams));

        report.fingerprint = fingerprint();
        return checks;
    }

    private Check verifyAttachmentHashes(WikiJsSource src, MigrationReport report) {
        record Att(long id, long pageId, String sha) {
        }
        List<Att> atts = jdbc.sql("SELECT id, page_id, sha256 FROM attachments ORDER BY id")
                .query((rs, n) -> new Att(rs.getLong(1), rs.getLong(2), rs.getString(3))).list();
        Map<Long, String> sourceSha = new HashMap<>();
        int ok = 0;
        for (Att a : atts) {
            String fileSha = AttachmentStorage.sha256(storage.read(a.pageId(), a.id()));
            boolean match = fileSha.equals(a.sha());
            Long assetId = report.attachmentSource.get(a.id());
            if (assetId != null) {
                String expected = sourceSha.computeIfAbsent(assetId, id -> AttachmentStorage.sha256(src.assetData(id)));
                match &= fileSha.equals(expected);
            }
            ok += match ? 1 : 0;
        }
        return new Check("첨부 sha256 = 파일 = 원본 BLOB", "전 건 (원본 에셋 유래 " + report.attachmentSource.size()
                + ")", ok + "/" + atts.size(), ok == atts.size());
    }

    private Check verifyFileReferences(List<String> contents) {
        int total = 0;
        int ok = 0;
        for (String c : contents) {
            Matcher m = FILE_URL.matcher(c);
            while (m.find()) {
                total++;
                String name = ContentConverter.decode(m.group(2));
                ok += count("SELECT count(*) FROM attachments WHERE page_id = ? AND filename = ?",
                        Long.parseLong(m.group(1)), name) == 1 ? 1 : 0;
            }
        }
        return ratio("본문 /p/{id}/files/ 참조 → 첨부 존재", ok, total);
    }

    private Check verifyTags(WikiJsSource src) {
        Map<Long, List<String>> srcTags = src.tagsByPage();
        int ok = 0;
        for (Map.Entry<Long, List<String>> e : srcTags.entrySet()) {
            Set<String> expected = TagNames.normalizeAll(e.getValue()).stream()
                    .map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            Set<String> actual = jdbc.sql(
                    "SELECT t.name FROM page_tags pt JOIN tags t ON t.id = pt.tag_id WHERE pt.page_id = ?")
                    .param(e.getKey()).query(String.class).list().stream()
                    .map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            ok += expected.equals(actual) ? 1 : 0;
        }
        return ratio("원본 pageTags 조합 보존 (정규화 후)", ok, srcTags.size());
    }

    /** 재실행 비교용: 문서·첨부·태그·리비전 내용 해시. */
    private String fingerprint() {
        StringBuilder s = new StringBuilder();
        jdbc.sql("SELECT id, parent_id, path, title, content, version FROM pages ORDER BY id")
                .query((rs, n) -> s.append(rs.getLong(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3)).append('|').append(rs.getString(4)).append('|')
                        .append(rs.getString(5)).append('|').append(rs.getInt(6)).append('\n')).list();
        jdbc.sql("SELECT page_id, filename, sha256 FROM attachments ORDER BY page_id, filename")
                .query((rs, n) -> s.append(rs.getLong(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3)).append('\n')).list();
        jdbc.sql("SELECT pt.page_id, t.name FROM page_tags pt JOIN tags t ON t.id = pt.tag_id ORDER BY 1, 2")
                .query((rs, n) -> s.append(rs.getLong(1)).append('#').append(rs.getString(2)).append('\n')).list();
        jdbc.sql("SELECT page_id, version, action, content FROM page_revisions ORDER BY page_id, version")
                .query((rs, n) -> s.append(rs.getLong(1)).append('v').append(rs.getInt(2)).append(rs.getString(3))
                        .append(rs.getString(4).hashCode()).append('\n')).list();
        return AttachmentStorage.sha256(s.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static Check ratio(String name, int ok, int total) {
        return new Check(name, total + "/" + total, ok + "/" + total, ok == total);
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static int matches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
