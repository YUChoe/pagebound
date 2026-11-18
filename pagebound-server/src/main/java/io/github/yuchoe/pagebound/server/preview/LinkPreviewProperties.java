package io.github.yuchoe.pagebound.server.preview;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 링크 미리보기 설정 (spec/features/F10-link-preview.md 4절). base-url이 비어 있는 GitLab 항목은 무시한다.
 * 토큰은 환경변수로만 주입하고 로그·API 응답에 넣지 않는다.
 */
@ConfigurationProperties("pagebound.link-preview")
public record LinkPreviewProperties(List<GitLab> gitlab, OpenGraph opengraph, Duration timeout,
                                    Integer perHostConcurrency) {

    public record GitLab(String name, String baseUrl, String token) {

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        public String base() {
            return baseUrl.strip().replaceAll("/+$", "");
        }

        /** 토큰이 로그에 찍히지 않도록 */
        @Override
        public String toString() {
            return "GitLab[" + name + ", " + baseUrl + ", token=" + (hasToken() ? "***" : "none") + "]";
        }
    }

    public record OpenGraph(boolean enabled, List<String> allowPrivateHosts) {
    }

    public LinkPreviewProperties {
        gitlab = gitlab == null ? List.of() : gitlab.stream().filter(GitLab::configured).toList();
        opengraph = opengraph == null ? new OpenGraph(true, List.of()) : opengraph;
        if (opengraph.allowPrivateHosts() == null) {
            opengraph = new OpenGraph(opengraph.enabled(), List.of());
        }
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        perHostConcurrency = perHostConcurrency == null ? 4 : perHostConcurrency;
    }
}
