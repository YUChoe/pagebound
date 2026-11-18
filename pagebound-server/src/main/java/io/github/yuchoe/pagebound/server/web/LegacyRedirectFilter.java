package io.github.yuchoe.pagebound.server.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import io.github.yuchoe.pagebound.core.page.PageReadService;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 레거시/이전 경로 301 리다이렉트 (spec/features/F3-tree-links.md 2절).
 * - Wiki.js URL: /{path}, /ko/{path} → /w/{현재 경로}
 * - 이동 전 경로: /w/{old} (활성 문서 없음 + alias 있음) → /w/{현재 경로}
 */
@Component
public class LegacyRedirectFilter extends OncePerRequestFilter {

    private static final List<String> RESERVED = List.of("/api/", "/p/", "/assets/", "/login", "/logout", "/edit/",
            "/history/", "/backlinks/", "/tags", "/recent", "/search", "/source/", "/favicon", "/index.html");

    private final PageReadService pages;

    public LegacyRedirectFilter(PageReadService pages) {
        this.pages = pages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod())) {
            Optional<String> target = redirectTarget(
                    UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()),
                            StandardCharsets.UTF_8));
            if (target.isPresent()) {
                response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                response.setHeader("Location", request.getContextPath() + "/w/"
                        + UriUtils.encodePath(target.get(), StandardCharsets.UTF_8));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    Optional<String> redirectTarget(String uri) {
        if (uri.startsWith("/w/")) {
            String path = WikiPaths.normalize(uri.substring(3));
            if (uri.endsWith(".md") || path.isEmpty() || pages.exists(path)) {
                return Optional.empty();
            }
            return pages.aliasTarget(path);
        }
        if (uri.equals("/") || RESERVED.stream().anyMatch(uri::startsWith)) {
            return Optional.empty();
        }
        String path = WikiPaths.normalize(uri);
        if (path.startsWith("ko/")) {
            path = path.substring(3);
        }
        return path.isEmpty() ? Optional.empty() : pages.aliasTarget(path);
    }
}
