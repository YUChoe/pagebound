package io.github.yuchoe.pagebound.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.yuchoe.pagebound.migrator.ContentConverter.Prepared;

class ContentConverterTest {

    private final ContentConverter converter = new ContentConverter();

    private String convert(String content, Set<String> assets) {
        Prepared p = converter.prepare(content, assets);
        return converter.render(p, a -> "/p/1/files/" + a.assetPath().substring(a.assetPath().lastIndexOf('/') + 1),
                d -> "/p/1/files/diagram-" + d.index() + ".svg", e -> null);
    }

    @Test
    void detailsBecomeCollapsedFence() {
        String src = "<details>\n  <summary>/etc/hosts</summary>\n  \n```\n127.0.0.1 localhost\n```\n  \n</details>\n";
        assertThat(convert(src, Set.of()))
                .isEqualTo("```title=\"/etc/hosts\" collapsed\n127.0.0.1 localhost\n```\n");
        String generic = "<details>\n<summary>접기</summary>\n```bash\nls\n```\n</details>";
        assertThat(convert(generic, Set.of())).isEqualTo("```bash collapsed\nls\n```");
    }

    @Test
    void assetLinksWithParenthesesAndBracketsAreRewritten() {
        Set<String> assets = Set.of("/nems/임시(20230524).xlsx", "/[naver_works]_a.docx", "/altibase_log.png");
        String src = "[임시(20230524).xlsx](/nems/임시(20230524).xlsx)\n"
                + "[[naver_works]_a.docx](/[naver_works]_a.docx)\n"
                + "![x](/altibase_log.png)\n";
        assertThat(convert(src, assets)).isEqualTo("[임시(20230524).xlsx](/p/1/files/임시(20230524).xlsx)\n"
                + "[[naver_works]_a.docx](/p/1/files/[naver_works]_a.docx)\n"
                + "![x](/p/1/files/altibase_log.png)\n");
    }

    @Test
    void pageLinksDokuAndCodeHandling() {
        String src = "[팀 서버](/팀서버) [개발용 DNS 설정](/개발용 DNS 설정) [x](/ko/nems/a#개요)\n"
                + "[[https://docs.gitlab.com/a.html]] [[http://h/x|Wiki 유지보수]] [[bash]]\n"
                + "`[t](/in/code)`\n";
        assertThat(convert(src, Set.of())).isEqualTo(
                "[[팀서버|팀 서버]] [[개발용 DNS 설정]] [[nems/a#개요|x]]\n"
                        + "<https://docs.gitlab.com/a.html> [Wiki 유지보수](http://h/x) [[bash]]\n"
                        + "`[t](/in/code)`\n");
    }

    @Test
    void calloutsStandaloneAndTrailing() {
        assertThat(convert("\n> 최신 버전\n{.is-info}\n\n# 다음", Set.of()))
                .isEqualTo("\n> [!INFO]\n> 최신 버전\n\n# 다음");
        assertThat(convert("> 백업 필수 {.is-warning}\n---", Set.of()))
                .isEqualTo("> [!WARNING]\n> 백업 필수\n---");
        assertThat(convert("- a\n- b\n{.grid-list}\n", Set.of())).isEqualTo("- a\n- b\n");
    }

    @Test
    void diagramBlockBecomesSvgImage() {
        String svg = java.util.Base64.getEncoder().encodeToString("<svg/>".getBytes());
        Prepared p = converter.prepare("앞\n```diagram\n" + svg + "\n```\n뒤", Set.of());
        assertThat(new String(p.diagrams().get(0))).isEqualTo("<svg/>");
        assertThat(converter.render(p, a -> null, d -> "/p/1/files/diagram-" + d.index() + ".svg", e -> null))
                .isEqualTo("앞\n![diagram-1](/p/1/files/diagram-1.svg)\n뒤");
    }
}
