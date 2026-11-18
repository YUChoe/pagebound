package io.github.yuchoe.pagebound.core.tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 태그 정규화 규칙 (spec/features/F4-tags.md 1절).
 * 콤마로 분리, 앞뒤 공백 제거, 연속 공백 1개, 대소문자 무시 중복 제거, 최대 40자.
 */
public final class TagNames {

    public static final int MAX_LENGTH = 40;

    private TagNames() {
    }

    /** 원문 하나(콤마 포함 가능)를 정규화된 태그 목록으로. */
    public static List<String> normalize(String raw) {
        return normalizeAll(List.of(raw == null ? "" : raw));
    }

    /** 여러 원문을 합쳐 정규화. 먼저 나온 표기를 유지한다. */
    public static List<String> normalizeAll(List<String> raws) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : raws) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String name = part.trim().replaceAll("\\s+", " ");
                if (name.isEmpty()) {
                    continue;
                }
                if (name.length() > MAX_LENGTH) {
                    name = name.substring(0, MAX_LENGTH).trim();
                }
                unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return new ArrayList<>(unique.values());
    }
}
