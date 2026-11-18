package io.github.yuchoe.pagebound.server.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.token.ApiTokenService;

/**
 * API 토큰 관리 (spec/features/F6-mcp-api.md 2절). /me/tokens는 웹 세션 전용, /admin/tokens는 ADMIN (SecurityConfig).
 */
@RestController
public class TokenController {

    /** expiresInDays: 30, 90 또는 null(무기한). */
    public record IssueRequest(String name, String scope, Integer expiresInDays) {
    }

    private final ApiTokenService tokens;
    private final CurrentUser currentUser;

    public TokenController(ApiTokenService tokens, CurrentUser currentUser) {
        this.tokens = tokens;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/me/tokens")
    public List<ApiTokenService.TokenInfo> mine(Authentication auth) {
        return tokens.list(currentUser.id(auth));
    }

    /** 응답의 token 원문은 이때 한 번만 내려간다. */
    @PostMapping("/api/v1/me/tokens")
    public ResponseEntity<ApiTokenService.Issued> issue(@RequestBody IssueRequest r, Authentication auth) {
        var issued = tokens.issue(currentUser.id(auth), r.name(), r.scope(), r.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @DeleteMapping("/api/v1/me/tokens/{id}")
    public ResponseEntity<Void> revokeMine(@PathVariable long id, Authentication auth) {
        long userId = currentUser.id(auth);
        tokens.revoke(id, userId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/admin/tokens")
    public List<ApiTokenService.TokenInfo> all() {
        return tokens.list(null);
    }

    @DeleteMapping("/api/v1/admin/tokens/{id}")
    public ResponseEntity<Void> forceRevoke(@PathVariable long id, Authentication auth) {
        tokens.revoke(id, null, currentUser.id(auth));
        return ResponseEntity.noContent().build();
    }
}
