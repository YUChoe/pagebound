package io.github.yuchoe.pagebound.migrator;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;
import io.github.yuchoe.pagebound.core.page.WikiPaths;

/**
 * Wiki.js 본문 → Pagebound 본문 변환 (spec/features/F8-migration.md 4절).
 * 1) 구조 변환(details, diagram, 콜아웃, 속성 줄) 후 2) 코드 영역 밖 링크를 찾아 3) 치환한다.
 */
final class ContentConverter {

    static final String NOTION_PREFIX = "https://s3-us-west-2.amazonaws.com/secure.notion-static.com/";

    private static final Pattern DETAILS = Pattern.compile(
            "<details>\\s*(?:<summary>(.*?)</summary>)?\\s*(`{3,}|~{3,})([^\\n]*)\\n(.*?)\\n[ \\t]*\\2\\s*</details>",
            Pattern.DOTALL);
    private static final Pattern DIAGRAM = Pattern.compile("(?m)^(`{3,})diagram[ \\t]*\\n([\\s\\S]*?)\\n\\1[ \\t]*$");
    /** 단독 줄 {.is-xxx} 또는 인용문 줄 끝 "> 내용 {.is-xxx}" */
    private static final Pattern CALLOUT_ATTR =
            Pattern.compile("^(>.*?)?\\s*\\{\\.is-(info|warning|success|danger)\\}\\s*$");
    private static final Pattern LIST_ATTR = Pattern.compile("^\\{\\.(grid-list|links-list)\\}\\s*$");
    private static final Pattern DOKU = Pattern.compile("\\[\\[([^\\[\\]]+?)\\]\\]");
    /**
     * 링크 텍스트는 대괄호 1단계 중첩, 대상은 괄호 1단계 중첩 허용 (파일명 "임시(20230524).xlsx", "[naver_works]_x.docx").
     * 교대 반복이 긴 줄에서 StackOverflowError를 내지 않도록 소유 수량자(++, *+)를 쓴다.
     */
    private static final Pattern MD_LINK = Pattern.compile(
            "(!?)\\[((?:[^\\[\\]\\n]++|\\[[^\\[\\]\\n]*+\\])*+)\\]\\(((?:[^()\\n]++|\\([^()\\n]*+\\))++)\\)");
    private static final Pattern EXTENSION = Pattern.compile(".*\\.[A-Za-z0-9]{1,5}$");
    private static final Set<String> GENERIC_SUMMARIES =
            Set.of("", "click to collapse", "click to expand", "접기", "펼치기", "보기", "더보기");
    private static final Map<String, String> CALLOUT_TYPES =
            Map.of("info", "INFO", "warning", "WARNING", "success", "TIP", "danger", "DANGER");

    /** 치환 대상. [start, end) 구간을 replacement로 바꾼다. */
    sealed interface Ref permits AssetRef, DiagramRef, ExternalImageRef, PageLinkRef, DokuUrlRef {
        int start();

        int end();
    }

    /** 링크 대상 부분만 치환. */
    record AssetRef(int start, int end, String assetPath) implements Ref {
    }

    record DiagramRef(int start, int end, int index) implements Ref {
    }

    record ExternalImageRef(int start, int end, String url) implements Ref {
    }

    /** 링크 전체 치환. */
    record PageLinkRef(int start, int end, String path, String fragment, String label) implements Ref {
    }

    record DokuUrlRef(int start, int end, String url, String label) implements Ref {
    }

    /** 변환 중간 결과. warnings는 리포트용 문장. */
    record Prepared(String text, List<Ref> refs, List<byte[]> diagrams, Map<String, Integer> counts,
                    List<String> warnings) {
        List<String> assetPaths() {
            return refs.stream().filter(r -> r instanceof AssetRef).map(r -> ((AssetRef) r).assetPath())
                    .distinct().toList();
        }
    }

    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    /** assetPaths: 원본 에셋 경로 집합 (/{folder}/{filename}). */
    Prepared prepare(String content, Set<String> assetPaths) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<byte[]> diagrams = new ArrayList<>();

        String text = convertDetails(content, counts);
        text = extractDiagrams(text, diagrams, warnings);
        text = convertAttributeLines(text, counts, warnings);

        List<MarkdownAnalyzer.Range> code = analyzer.codeRanges(text);
        List<Ref> refs = new ArrayList<>();
        scanDoku(text, code, refs, counts, warnings);
        scanMarkdownLinks(text, code, assetPaths, refs, warnings);
        refs.sort(Comparator.comparingInt(Ref::start));
        List<Ref> nonOverlapping = new ArrayList<>();
        int last = -1;
        for (Ref r : refs) {
            if (r.start() >= last) {
                nonOverlapping.add(r);
                last = r.end();
            }
        }
        return new Prepared(text, nonOverlapping, diagrams, counts, warnings);
    }

    /** 치환 적용. 각 함수가 null을 반환하면 원문 유지. */
    String render(Prepared p, Function<AssetRef, String> assetUrl, Function<DiagramRef, String> diagramUrl,
                  Function<ExternalImageRef, String> imageUrl) {
        StringBuilder out = new StringBuilder();
        int last = 0;
        String text = p.text();
        for (Ref ref : p.refs()) {
            String original = text.substring(ref.start(), ref.end());
            String replacement = switch (ref) {
                case AssetRef a -> assetUrl.apply(a);
                case DiagramRef d -> diagramUrl.apply(d);
                case ExternalImageRef e -> imageUrl.apply(e);
                case PageLinkRef l -> wikiLink(l);
                case DokuUrlRef d -> d.label() == null || d.label().isEmpty()
                        ? "<" + d.url() + ">" : "[" + d.label() + "](" + d.url() + ")";
            };
            out.append(text, last, ref.start()).append(replacement == null ? original : replacement);
            last = ref.end();
        }
        return out.append(text.substring(last)).toString();
    }

    private static String wikiLink(PageLinkRef l) {
        StringBuilder s = new StringBuilder("[[").append(l.path());
        if (!l.fragment().isEmpty()) {
            s.append('#').append(l.fragment());
        }
        if (!l.label().isBlank() && !l.label().trim().equals(WikiPaths.lastSegment(l.path()))) {
            s.append('|').append(l.label().trim());
        }
        return s.append("]]").toString();
    }

    private static String convertDetails(String text, Map<String, Integer> counts) {
        Matcher m = DETAILS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String summary = m.group(1) == null ? "" : m.group(1).replaceAll("<[^>]+>", "").trim();
            String title = GENERIC_SUMMARIES.contains(summary.toLowerCase(Locale.ROOT)) ? null : summary;
            List<String> info = new ArrayList<>();
            String lang = m.group(3).trim();
            if (!lang.isEmpty()) {
                info.add(lang);
            }
            if (title != null) {
                info.add("title=\"" + title.replace('"', '\'') + "\"");
            }
            info.add("collapsed");
            String fence = m.group(2);
            String block = fence + String.join(" ", info) + "\n" + m.group(4) + "\n" + fence;
            m.appendReplacement(out, Matcher.quoteReplacement(block));
            counts.merge("details→collapsed", 1, Integer::sum);
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String extractDiagrams(String text, List<byte[]> diagrams, List<String> warnings) {
        Matcher m = DIAGRAM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            try {
                byte[] svg = Base64.getMimeDecoder().decode(m.group(2).trim());
                diagrams.add(svg);
                String image = "![diagram-" + diagrams.size() + "](diagram:" + diagrams.size() + ")";
                m.appendReplacement(out, Matcher.quoteReplacement(image));
            } catch (IllegalArgumentException e) {
                warnings.add("diagram base64 디코드 실패 → 원문 유지");
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {.is-xxx} → 바로 위 인용문을 콜아웃으로, {.grid-list}/{.links-list} 줄 제거. */
    private static String convertAttributeLines(String text, Map<String, Integer> counts, List<String> warnings) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            Matcher callout = CALLOUT_ATTR.matcher(lines.get(i));
            if (callout.matches()) {
                String type = callout.group(2);
                int last = i;
                if (callout.group(1) != null) {
                    lines.set(i, callout.group(1));   // 인용문 줄 끝 속성 제거
                } else {
                    lines.remove(i);                   // 단독 속성 줄 제거
                    last = i - 1;
                }
                int first = last + 1;
                while (first > 0 && lines.get(first - 1).startsWith(">")) {
                    first--;
                }
                if (first <= last) {
                    lines.add(first, "> [!" + CALLOUT_TYPES.get(type) + "]");
                    counts.merge("callout", 1, Integer::sum);
                } else {
                    warnings.add("{.is-" + type + "} 위에 인용문 없음 → 속성 줄만 제거");
                }
                i = Math.max(-1, first - 1);
            } else if (LIST_ATTR.matcher(lines.get(i)).matches()) {
                lines.remove(i--);
                counts.merge("list-attr 제거", 1, Integer::sum);
            }
        }
        return String.join("\n", lines);
    }

    private static void scanDoku(String text, List<MarkdownAnalyzer.Range> code, List<Ref> refs,
                                 Map<String, Integer> counts, List<String> warnings) {
        Matcher m = DOKU.matcher(text);
        while (m.find()) {
            if (MarkdownAnalyzer.inAny(code, m.start())) {
                continue;
            }
            String inner = m.group(1);
            int bar = inner.indexOf('|');
            String target = (bar < 0 ? inner : inner.substring(0, bar)).trim();
            String label = bar < 0 ? null : inner.substring(bar + 1).replaceAll("\\s+", " ").trim();
            if (target.matches("(?i)https?://\\S+")) {
                refs.add(new DokuUrlRef(m.start(), m.end(), target, label));
                counts.merge("doku URL→링크", 1, Integer::sum);
            } else if (target.contains("\\")) {
                warnings.add("DokuWiki UNC 경로 원문 유지: " + m.group());
            } else {
                counts.merge("doku→위키 링크 유지", 1, Integer::sum);
            }
        }
    }

    private static void scanMarkdownLinks(String text, List<MarkdownAnalyzer.Range> code, Set<String> assetPaths,
                                          List<Ref> refs, List<String> warnings) {
        Map<String, String> assetsLower = new java.util.HashMap<>();
        assetPaths.forEach(p -> assetsLower.putIfAbsent(p.toLowerCase(Locale.ROOT), p));
        Matcher m = MD_LINK.matcher(text);
        while (m.find()) {
            if (MarkdownAnalyzer.inAny(code, m.start())) {
                continue;
            }
            boolean image = !m.group(1).isEmpty();
            String target = m.group(3).trim();
            int ts = m.start(3);
            int te = m.end(3);
            if (target.startsWith("diagram:")) {
                refs.add(new DiagramRef(ts, te, Integer.parseInt(target.substring(8))));
            } else if (target.startsWith(NOTION_PREFIX) && image) {
                refs.add(new ExternalImageRef(ts, te, target));
            } else if (target.startsWith("/")) {
                String pathPart = target.split("[?#]", 2)[0];
                String decoded = decode(pathPart);
                String asset = assetPaths.contains(decoded) ? decoded
                        : assetsLower.get(decoded.toLowerCase(Locale.ROOT));
                if (asset != null) {
                    refs.add(new AssetRef(ts, te, asset));
                } else if (EXTENSION.matcher(decoded).matches()) {
                    warnings.add("존재하지 않는 에셋 참조 원문 유지: " + target);
                } else if (!image) {
                    String path = decoded.replaceFirst("^/(ko/)?", "");
                    String fragment = target.contains("#") ? decode(target.substring(target.indexOf('#') + 1)) : "";
                    path = WikiPaths.normalize(path);
                    if (!path.isEmpty()) {
                        refs.add(new PageLinkRef(m.start(), m.end(), path, fragment, m.group(2)));
                    }
                }
            }
        }
    }

    /** 퍼센트 디코드 ('+'는 공백으로 바꾸지 않음). 잘못된 인코딩이면 원문. */
    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
