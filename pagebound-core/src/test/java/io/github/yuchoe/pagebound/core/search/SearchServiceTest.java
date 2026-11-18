package io.github.yuchoe.pagebound.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.yuchoe.pagebound.core.CoreFixture;
import io.github.yuchoe.pagebound.core.search.SearchService.Hit;

class SearchServiceTest {

    @TempDir
    Path dir;
    CoreFixture f;

    @BeforeEach
    void setUp() {
        f = new CoreFixture(dir);
        f.page("nems/통계", "# 개요\n파티션 테이블 통계 설명\n## 설정\n```properties\nmax_connections=200\n```\n", "nems");
        f.page("nems/Altibase/라이선스", "altibase 라이선스 만료 시 서버가 내려간다. nginx 와 무관", "altibase", "nems");
        f.page("git/설치", "Apache httpd 와 nginx 설치. /etc/hosts 수정, nes-api 배포", "git");
        f.page("통계", "다른 문서", "기타");
        long draft = f.writes.createDraft(1);
        f.writes.replaceContent(draft, "파티션 초안 문서", null, 1, "web");
    }

    @AfterEach
    void close() {
        f.close();
    }

    private List<String> paths(String q) {
        return f.search.search(q, null, null, "relevance", 1, 20).items().stream().map(Hit::path).toList();
    }

    @Test
    void parser() {
        SearchQuery q = SearchQuery.parse("altibase \"Connection refused\" -apache tag:nems tag:db path:/nems/ in:code \"open");
        assertThat(q.needles()).containsExactly("altibase", "Connection refused", "\"open");
        assertThat(q.excludes()).containsExactly("apache");
        assertThat(q.tags()).containsExactly("nems", "db");
        assertThat(q.path()).isEqualTo("nems");
    }

    @Test
    void koreanShortAndLongTermsCodeAndDraftExcluded() {
        assertThat(paths("통계")).containsExactly("통계", "nems/통계");   // 2자 LIKE, 제목 완전 일치가 먼저
        assertThat(paths("파티션")).containsExactly("nems/통계");          // 3자 FTS, 초안 제외
        var hit = f.search.search("max_connections", null, null, null, 1, 20).items().getFirst();
        assertThat(hit.path()).isEqualTo("nems/통계");
        assertThat(hit.snippets().getFirst().inCode()).isTrue();
        assertThat(hit.snippets().getFirst().text()).contains("\u0002max_connections\u0003");
        assertThat(hit.section().number()).isEqualTo("1.1.");
    }

    @Test
    void phraseExcludeTagPathAndSpecialCharacters() {
        assertThat(paths("\"라이선스 만료\"")).containsExactly("nems/Altibase/라이선스");
        assertThat(paths("nginx")).containsExactlyInAnyOrder("nems/Altibase/라이선스", "git/설치");
        assertThat(paths("nginx -apache")).containsExactly("nems/Altibase/라이선스");
        assertThat(paths("nginx tag:git")).containsExactly("git/설치");
        // 하위 트리 필터: 자동 생성된 부모(nems, nems/Altibase)도 포함
        assertThat(paths("path:nems")).containsExactlyInAnyOrder("nems", "nems/통계", "nems/Altibase", "nems/Altibase/라이선스");
        assertThat(paths("nes-api")).containsExactly("git/설치");
        assertThat(paths("/etc/hosts")).containsExactly("git/설치");
        assertThat(paths("")).isEmpty();
    }

    @Test
    void shortTermSnippetIsMarked() {
        f.page("짧은", "# 개요\n앞부분 [[home]]\n## 설정\n본문\n");
        var hit = f.search.search("본문", null, null, null, 1, 20).items().getFirst();
        String text = hit.snippets().getFirst().text();
        assertThat(text).as("codepoints %s", text.codePoints().mapToObj(Integer::toHexString).toList())
                .contains("\u0002본문\u0003");
    }

    @Test
    void facetsSortAndSuggest() {
        var result = f.search.search("nems", null, null, null, 1, 20);
        assertThat(result.tagFacets()).extracting(SearchService.Facet::name).contains("nems");
        assertThat(result.pathFacets()).extracting(SearchService.Facet::name).containsExactly("nems");
        assertThat(f.search.search("tag:nems", null, null, "updated", 1, 1).total()).isEqualTo(2);

        var suggestions = f.search.suggest("통계", 8);
        assertThat(suggestions.getFirst().path()).isEqualTo("통계");
        assertThat(suggestions.getFirst().exact()).isTrue();
        assertThat(suggestions).extracting(SearchService.Suggestion::path).contains("nems/통계");
    }
}
