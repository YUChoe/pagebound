package io.github.yuchoe.pagebound.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.yuchoe.pagebound.core.CoreFixture;

/**
 * 합성 2,000문서 검색 성능 (spec/features/F11-search.md 11절: API p95 300ms, 자동완성 50ms 이내).
 */
class SearchPerformanceTest {

    private static final String[] WORDS = {"서버", "설정", "통계", "장애", "설치", "로그", "파티션", "알티베이스", "라이선스",
        "nginx", "apache", "altibase", "max_connections", "connection", "refused", "timeout", "java", "spring",
        "nes-api", "/etc/hosts", "배포", "패키지", "버전", "인증서", "백업", "복구", "쿼리", "인덱스", "메모리", "디스크"};

    @TempDir
    Path dir;
    CoreFixture f;

    @BeforeEach
    void setUp() {
        f = new CoreFixture(dir);
        Random random = new Random(42);
        f.jdbc.sql("BEGIN").update();
        for (int i = 0; i < 2000; i++) {
            StringBuilder body = new StringBuilder("# 개요\n");
            while (body.length() < 6000) {
                body.append(WORDS[random.nextInt(WORDS.length)]).append(random.nextInt(10) == 0 ? "\n" : " ");
            }
            String path = "area" + (i % 20) + "/doc" + i;
            f.jdbc.sql("INSERT INTO pages (slug, path, title, content, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params("doc" + i, path, "문서 " + WORDS[i % WORDS.length] + " " + i, body.toString(),
                            "2026-01-01T00:00:00Z", String.format("2026-01-01T00:%02d:00Z", i % 60))
                    .update();
        }
        f.jdbc.sql("COMMIT").update();
    }

    @AfterEach
    void close() {
        f.close();
    }

    private static long p95(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000;
    }

    @Test
    void searchP95Under300msAndSuggestUnder50ms() {
        String[] queries = {"altibase", "통계", "nginx 설정", "\"connection refused\"", "max_connections", "서버 -apache",
            "path:area3 로그", "nes-api", "/etc/hosts", "인증서 백업 복구"};
        f.search.search("warmup", null, null, null, 1, 20);
        List<Long> search = new ArrayList<>();
        List<Long> suggest = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (String q : queries) {
                long t = System.nanoTime();
                f.search.search(q, null, null, "relevance", 1, 20);
                search.add(System.nanoTime() - t);
                t = System.nanoTime();
                f.search.suggest(q, 8);
                suggest.add(System.nanoTime() - t);
            }
        }
        System.out.printf("search p95=%dms suggest p95=%dms (n=%d)%n", p95(search), p95(suggest), search.size());
        assertThat(p95(search)).isLessThan(300);
        assertThat(p95(suggest)).isLessThan(50);
    }
}
