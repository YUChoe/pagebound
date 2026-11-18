package io.github.yuchoe.pagebound.migrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * migration-report.md: 건수 요약, 검증 결과, 경고/미해결 항목.
 */
final class MigrationReport {

    record Check(String name, String expected, String actual, boolean passed) {
    }

    private final Map<String, Object> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> items = new LinkedHashMap<>();
    /** 첨부 id → 원본 asset id (검증용). */
    final Map<Long, Long> attachmentSource = new LinkedHashMap<>();
    String fingerprint = "";

    void count(String key, Object value) {
        counts.put(key, value);
    }

    void add(String key, int delta) {
        counts.merge(key, delta, (a, b) -> (Integer) a + (Integer) b);
    }

    void item(String section, String line) {
        items.computeIfAbsent(section, k -> new ArrayList<>()).add(line);
    }

    void write(Path file, List<Check> checks) {
        StringBuilder md = new StringBuilder("# Pagebound 마이그레이션 리포트\n\n");
        md.append("- 실행 시각: ").append(Instant.now()).append('\n');
        md.append("- 지문(fingerprint): `").append(fingerprint).append("`\n\n");
        md.append("## 검증 (spec/features/F8-migration.md 6절)\n\n| 검사 | 기대 | 실제 | 결과 |\n|------|------|------|------|\n");
        for (Check c : checks) {
            md.append("| ").append(c.name()).append(" | ").append(c.expected()).append(" | ").append(c.actual())
                    .append(" | ").append(c.passed() ? "통과" : "실패").append(" |\n");
        }
        md.append("\n## 건수\n\n| 항목 | 값 |\n|------|----|\n");
        counts.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        items.forEach((section, lines) -> {
            md.append("\n## ").append(section).append(" (").append(lines.size()).append(")\n\n");
            lines.forEach(l -> md.append("- ").append(l.replace("\n", " ")).append('\n'));
        });
        try {
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
