package io.github.yuchoe.pagebound.core.markdown;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import io.github.yuchoe.pagebound.core.page.WikiPaths;

/**
 * 서버 측 마크다운 분석: 코드 영역, 위키 링크, 섹션(헤딩) 분할.
 * 클라이언트 렌더러(markdown-it)와 같은 CommonMark 규칙을 따른다.
 */
public final class MarkdownAnalyzer {

    /** [[대상#섹션|표시]] 형식. 대상에는 [ ] | # 와 줄바꿈이 올 수 없다. */
    private static final Pattern WIKI_LINK =
            Pattern.compile("\\[\\[([^\\[\\]|#\\n]*)(#[^\\[\\]|\\n]*)?(?:\\|([^\\[\\]\\n]*))?\\]\\]");

    private static final Parser PARSER = Parser.builder().build();

    /** 원문 내 [start, end) 구간. */
    public record Range(int start, int end) {
        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    /** 위키 링크. path는 현재 문서 기준으로 해석한 절대 경로 (해석 불가 시 빈 문자열). */
    public record WikiLink(int start, int end, String rawTarget, String path, String fragment, String label) {
    }

    /** 섹션. index는 1부터, number는 "2.1." 형식, end는 다음 동급 이상 헤딩 시작 또는 문서 끝. */
    public record Section(int index, String number, int level, String title, int start, int end) {
    }

    /** 펜스/들여쓰기 코드블록과 인라인 코드 구간. 오프셋 오름차순. */
    public List<Range> codeRanges(String markdown) {
        List<Range> ranges = new ArrayList<>();
        for (Node node : PARSER.parse(markdown).getDescendants()) {
            if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof Code) {
                ranges.add(new Range(node.getStartOffset(), node.getEndOffset()));
            }
        }
        ranges.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return ranges;
    }

    public static boolean inAny(List<Range> ranges, int offset) {
        for (Range r : ranges) {
            if (r.contains(offset)) {
                return true;
            }
            if (r.start() > offset) {
                return false;
            }
        }
        return false;
    }

    /** 코드 영역 밖의 위키 링크. */
    public List<WikiLink> wikiLinks(String markdown, String currentPath) {
        List<Range> code = codeRanges(markdown);
        List<WikiLink> links = new ArrayList<>();
        Matcher m = WIKI_LINK.matcher(markdown);
        while (m.find()) {
            if (inAny(code, m.start())) {
                continue;
            }
            String raw = m.group(1);
            String fragment = m.group(2) == null ? "" : m.group(2).substring(1).trim();
            String label = m.group(3) == null ? null : m.group(3).trim();
            links.add(new WikiLink(m.start(), m.end(), raw, WikiPaths.resolve(currentPath, raw), fragment, label));
        }
        return links;
    }

    /** 클라이언트 JS String.trim()과 같은 범위(공백·NBSP·BOM 등 유니코드 공백) 제거. Java trim()/strip()은 NBSP를 남긴다. */
    private static final Pattern EDGE_SPACE = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");

    static String trimTitle(String raw) {
        return EDGE_SPACE.matcher(raw).replaceAll("");
    }

    /** ATX/Setext 헤딩 기준 섹션 목록. 번호는 중첩 깊이 기준 (헤딩 레벨을 건너뛰어도 1.1. 형태 유지). */
    public List<Section> sections(String markdown) {
        List<Heading> headings = new ArrayList<>();
        for (Node node : PARSER.parse(markdown).getDescendants()) {
            if (node instanceof Heading h) {
                headings.add(h);
            }
        }
        List<Section> sections = new ArrayList<>();
        Deque<Integer> openLevels = new ArrayDeque<>();
        int[] counters = new int[7];
        for (int i = 0; i < headings.size(); i++) {
            Heading h = headings.get(i);
            int level = h.getLevel();
            while (!openLevels.isEmpty() && openLevels.peek() >= level) {
                openLevels.pop();
            }
            int depth = openLevels.size();
            counters[depth]++;
            for (int d = depth + 1; d < counters.length; d++) {
                counters[d] = 0;
            }
            openLevels.push(level);

            StringBuilder number = new StringBuilder();
            for (int d = 0; d <= depth; d++) {
                number.append(counters[d]).append('.');
            }
            int end = markdown.length();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).getLevel() <= level) {
                    end = headings.get(j).getStartOffset();
                    break;
                }
            }
            sections.add(new Section(i + 1, number.toString(), level, trimTitle(h.getText().toString()),
                    h.getStartOffset(), end));
        }
        return sections;
    }
}
