package io.github.yuchoe.pagebound.server.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import io.github.yuchoe.pagebound.core.token.ApiTokenService;

/**
 * Bearer 토큰 인증 (spec/features/F6-mcp-api.md 1절). 실제 권한 = min(scope, 사용자 role).
 * 토큰은 관리 기능(ADMIN)을 갖지 않는다: write 토큰의 최대 권한은 EDITOR.
 */
public final class TokenAuthentication extends AbstractAuthenticationToken {

    private final ApiTokenService.Principal token;

    public TokenAuthentication(ApiTokenService.Principal token) {
        super(authorities(token));
        this.token = token;
        setAuthenticated(true);
    }

    static List<GrantedAuthority> authorities(ApiTokenService.Principal token) {
        boolean writer = "write".equals(token.scope()) && !"VIEWER".equals(token.role());
        return List.of(new SimpleGrantedAuthority(writer ? "ROLE_EDITOR" : "ROLE_VIEWER"));
    }

    public ApiTokenService.Principal token() {
        return token;
    }

    public boolean canWrite() {
        return getAuthorities().stream().anyMatch(a -> "ROLE_EDITOR".equals(a.getAuthority()));
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }

    @Override
    public String getName() {
        return token.email();
    }
}
