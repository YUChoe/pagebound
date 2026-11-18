package io.github.yuchoe.pagebound.core.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.yuchoe.pagebound.core.page.WikiPaths;

/**
 * 검색 질의 파서 (spec/features/F11-search.md 4절 P0).
 * a b(AND), "구문", -제외, tag:x, path:x. 짝이 안 맞는 따옴표는 일반 문자. in:·author:(P1·P2)는 무시.
 */
public record SearchQuery(List<String> needles, List<String> excludes, List<String> tags, String path) {

    private static final Pattern TOKEN = Pattern.compile("-?\"[^\"]*\"|\\S+");

    public static SearchQuery parse(String q) {
        List<String> needles = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        String path = null;
        Matcher m = TOKEN.matcher(q == null ? "" : q);
        while (m.find()) {
            String token = m.group();
            boolean negative = token.startsWith("-") && token.length() > 1;
            String body = negative ? token.substring(1) : token;
            if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length() - 1).trim();
            } else if (!negative && token.startsWith("tag:")) {
                if (token.length() > 4) {
                    tags.add(token.substring(4));
                }
                continue;
            } else if (!negative && token.startsWith("path:")) {
                path = WikiPaths.normalize(token.substring(5));
                continue;
            } else if (!negative && (token.startsWith("in:") || token.startsWith("author:"))) {
                continue;
            }
            if (body.isEmpty()) {
                continue;
            }
            (negative ? excludes : needles).add(body);
        }
        return new SearchQuery(List.copyOf(needles), List.copyOf(excludes), List.copyOf(tags),
                path == null || path.isEmpty() ? null : path);
    }
}
