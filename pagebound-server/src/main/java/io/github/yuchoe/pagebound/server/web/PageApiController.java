package io.github.yuchoe.pagebound.server.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;
import io.github.yuchoe.pagebound.core.page.PageReadService;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;
import io.github.yuchoe.pagebound.core.user.UserStore;

/**
 * 조회 API (spec/features/F3-tree-links.md 7절, F5-page-view.md 5절).
 */
@RestController
@RequestMapping("/api/v1")
public class PageApiController {

    private final PageReadService pages;
    private final UserStore users;
    private final RuntimeSettings settings;

    public PageApiController(PageReadService pages, UserStore users,
                             RuntimeSettings settings) {
        this.pages = pages;
        this.users = users;
        this.settings = settings;
    }

    @GetMapping("/tree")
    public List<PageReadService.TreeNode> tree(@RequestParam(required = false) Long parentId,
                                                @RequestParam(defaultValue = "1") int depth) {
        return pages.tree(parentId, Math.clamp(depth, 1, 20));
    }

    /** 없으면 404 + 이전 경로면 redirect 대상 경로. */
    @GetMapping("/pages/by-path")
    public ResponseEntity<?> byPath(@RequestParam String path) {
        String normalized = WikiPaths.normalize(path);
        return pages.view(normalized).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", "NOT_FOUND");
                    body.put("redirect", pages.aliasTarget(normalized).orElse(null));
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
                });
    }

    @GetMapping("/pages/outline")
    public ResponseEntity<List<MarkdownAnalyzer.Section>> outline(@RequestParam String path) {
        return pages.outline(WikiPaths.normalize(path)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/recent")
    public List<PageReadService.RecentChange> recent(@RequestParam(defaultValue = "10") int limit) {
        return pages.recent(Math.clamp(limit, 1, 100));
    }

    /** 현재 사용자. CsrfToken 접근으로 SPA용 XSRF-TOKEN 쿠키를 발급한다. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication, CsrfToken csrf) {
        csrf.getToken();
        Map<String, Object> body = new HashMap<>();
        body.put("anonymousRead", settings.anonymousRead());
        body.put("siteName", settings.siteName());
        body.put("homePath", settings.homePath());
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        body.put("authenticated", authenticated);
        if (authenticated) {
            users.findByEmail(authentication.getName()).ifPresent(u -> {
                body.put("name", u.name());
                body.put("email", u.email());
                body.put("role", u.role());
                body.put("mustChangePassword", u.mustChangePassword());
            });
        }
        return body;
    }
}
