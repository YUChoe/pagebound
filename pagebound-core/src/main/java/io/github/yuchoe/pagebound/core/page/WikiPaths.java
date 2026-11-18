package io.github.yuchoe.pagebound.core.page;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서 경로 규칙 (spec/features/F3-tree-links.md).
 * 경로는 앞뒤 '/'가 없는 slug 결합 문자열이다. 예: nems/고도화/통계
 */
public final class WikiPaths {

    private static final String FORBIDDEN_SLUG_CHARS = "/#?|[]";

    private WikiPaths() {
    }

    /** 앞뒤 공백·슬래시 제거, 빈 세그먼트 제거, 세그먼트별 trim. */
    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String s : path.split("/")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                segments.add(t);
            }
        }
        return String.join("/", segments);
    }

    public static String join(String parentPath, String slug) {
        String parent = normalize(parentPath);
        return parent.isEmpty() ? slug : parent + "/" + slug;
    }

    public static String parentOf(String path) {
        String p = normalize(path);
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    public static String lastSegment(String path) {
        String p = normalize(path);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /**
     * 위키 링크 대상을 절대 경로로 해석한다.
     * "/하위" → 현재 문서의 하위, "../" → 상위, "../형제" → 형제, 그 외 → 절대 경로.
     * 루트보다 위로 올라가면 빈 문자열.
     */
    public static String resolve(String currentPath, String target) {
        String t = target == null ? "" : target.trim();
        if (t.startsWith("/")) {
            return normalize(join(currentPath, normalize(t)));
        }
        if (t.startsWith("../") || t.equals("..")) {
            String base = normalize(currentPath);
            String rest = t;
            while (rest.startsWith("../") || rest.equals("..")) {
                if (base.isEmpty()) {
                    return "";
                }
                base = parentOf(base);
                rest = rest.equals("..") ? "" : rest.substring(3);
            }
            String r = normalize(rest);
            return r.isEmpty() ? base : normalize(join(base, r));
        }
        return normalize(t);
    }

    /** slug 유효성: 비어 있지 않고 금지 문자(/ # ? | [ ])가 없을 것. */
    public static boolean isValidSlug(String slug) {
        if (slug == null || slug.isBlank() || !slug.equals(slug.trim())) {
            return false;
        }
        for (char c : FORBIDDEN_SLUG_CHARS.toCharArray()) {
            if (slug.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }
}
