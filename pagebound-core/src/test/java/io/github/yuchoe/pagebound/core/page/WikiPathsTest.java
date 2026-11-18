package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.yuchoe.pagebound.core.tag.TagNames;

class WikiPathsTest {

    @Test
    void normalizeAndJoin() {
        assertThat(WikiPaths.normalize(" /nems//고도화/ 통계 /")).isEqualTo("nems/고도화/통계");
        assertThat(WikiPaths.join("", "home")).isEqualTo("home");
        assertThat(WikiPaths.join("nems", "통계")).isEqualTo("nems/통계");
        assertThat(WikiPaths.parentOf("nems/통계")).isEqualTo("nems");
        assertThat(WikiPaths.lastSegment("nems/통계")).isEqualTo("통계");
    }

    @Test
    void slugValidation() {
        assertThat(WikiPaths.isValidSlug("개발용 DNS 설정")).isTrue();
        assertThat(WikiPaths.isValidSlug("a/b")).isFalse();
        assertThat(WikiPaths.isValidSlug("a|b")).isFalse();
        assertThat(WikiPaths.isValidSlug(" a")).isFalse();
    }

    @Test
    void tagNormalization() {
        assertThat(TagNames.normalize("mqtt, broker, mosquitto, topic"))
                .containsExactly("mqtt", "broker", "mosquitto", "topic");
        assertThat(TagNames.normalize("엘라스틱, elastic, elastic")).containsExactly("엘라스틱", "elastic");
        assertThat(TagNames.normalizeAll(java.util.List.of("Java", "java ", "abc   114")))
                .containsExactly("Java", "abc 114");
    }
}
