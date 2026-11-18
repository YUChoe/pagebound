package io.github.yuchoe.pagebound.server.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.yuchoe.pagebound.core.user.UserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 웹 세션 사용자 재확인 (spec/features/F12-admin.md 5절): 비활성화·역할 변경된 사용자의 기존 세션을 다음 요청에서 만료한다.
 * 세션 인증만 대상(토큰은 ApiTokenService가 매 요청 확인).
 */
public class SessionUserCheckFilter extends OncePerRequestFilter {

    /** 폼 로그인 성공 시 세션에 남기는 표식 (SecurityConfig successHandler) */
    public static final String LOGIN_MARK = "pb.login";

    private final UserStore users;

    public SessionUserCheckFilter(UserStore users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession existing = request.getSession(false);
        boolean sessionLogin = existing != null && existing.getAttribute(LOGIN_MARK) != null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (sessionLogin && auth instanceof UsernamePasswordAuthenticationToken && auth.isAuthenticated()) {
            boolean valid = users.findByEmail(auth.getName())
                    .filter(u -> u.active())
                    .filter(u -> auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + u.role())))
                    .isPresent();
            if (!valid) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
