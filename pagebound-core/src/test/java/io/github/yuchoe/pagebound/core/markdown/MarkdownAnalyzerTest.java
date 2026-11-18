package io.github.yuchoe.pagebound.core.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer.Section;
import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer.WikiLink;

class MarkdownAnalyzerTest {

    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    @Test
    void wikiLinksSkipFencedIndentedAndInlineCode() {
        String md = """
                [[nems/통계]] 와 [[nems/고도화/통계#개요|통계 문서]]

                ```bash
                if [[ -f /etc/hosts ]]; then echo ok; fi
                ```

                    [[ -d /tmp ]] && echo indented

                인라인 `[[ -z "$x" ]]` 코드
                """;
        List<WikiLink> links = analyzer.wikiLinks(md, "home");
        assertThat(links).extracting(WikiLink::path).containsExactly("nems/통계", "nems/고도화/통계");
        assertThat(links.get(1).fragment()).isEqualTo("개요");
        assertThat(links.get(1).label()).isEqualTo("통계 문서");
        assertThat(links.get(0).label()).isNull();
    }

    @Test
    void relativeWikiLinksResolveAgainstCurrentPath() {
        String md = "[[/하위]] [[../]] [[../형제]] [[../../x]]";
        List<WikiLink> links = analyzer.wikiLinks(md, "nems/고도화");
        assertThat(links).extracting(WikiLink::path)
                .containsExactly("nems/고도화/하위", "nems", "nems/형제", "x");
        assertThat(analyzer.wikiLinks("[[../../../x]]", "a/b").get(0).path()).isEmpty();
    }

    @Test
    void sectionsHandleAtxSetextAndIgnoreHashInFence() {
        String md = """
                개요
                ====
                본문

                ```
                # 코드 안의 샵은 헤딩이 아님
                ```

                ## 설정
                ### 설정파일
                설정
                -----
                # 로드맵
                """;
        List<Section> sections = analyzer.sections(md);
        assertThat(sections).extracting(Section::title)
                .containsExactly("개요", "설정", "설정파일", "설정", "로드맵");
        assertThat(sections).extracting(Section::number)
                .containsExactly("1.", "1.1.", "1.1.1.", "1.2.", "2.");
        Section first = sections.get(0);
        assertThat(md.substring(first.start(), first.end())).contains("코드 안의 샵").doesNotContain("# 로드맵");
        assertThat(sections.get(4).end()).isEqualTo(md.length());
    }

    @Test
    void sectionNumberingSurvivesSkippedLevels() {
        String md = "## A\n#### B\n## C\n";
        assertThat(analyzer.sections(md)).extracting(Section::number).containsExactly("1.", "1.1.", "2.");
    }

    @Test
    void titleTrimsNbspLikeJavaScript() {
        assertThat(analyzer.sections("## jar 사용 \n").get(0).title()).isEqualTo("jar 사용");
    }
}
