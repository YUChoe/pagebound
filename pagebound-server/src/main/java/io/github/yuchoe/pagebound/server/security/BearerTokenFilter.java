package io.github.yuchoe.pagebound.server.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.yuchoe.pagebound.core.token.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization: Bearer 토큰 인증. 세션에 저장하지 않는다(요청 단위).
 * 잘못된 토큰은 401, REST 호출이 한도를 넘으면 429 (MCP는 도구 결과로 RATE_LIMITED를 돌려준다).
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final ApiTokenService tokens;
    private final TokenRateLimiter rateLimiter;

    public BearerTokenFilter(ApiTokenService tokens, TokenRateLimiter rateLimiter) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    public static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    public static boolean isMcp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/mcp") || uri.startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        var principal = tokens.authenticate(request.getHeader("Authorization").substring(PREFIX.length()).strip());
        if (principal.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            error(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid, expired or revoked token");
            return;
        }
        if (!isMcp(request) && !rateLimiter.tryAcquire(principal.get().tokenId())) {
            error(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "rate limit exceeded: " + rateLimiter.limit() + " requests per minute");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new TokenAuthentication(principal.get()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static void error(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
