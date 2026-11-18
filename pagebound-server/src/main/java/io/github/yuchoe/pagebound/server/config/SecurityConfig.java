package io.github.yuchoe.pagebound.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import io.github.yuchoe.pagebound.core.token.ApiTokenService;
import io.github.yuchoe.pagebound.core.user.UserStore;
import io.github.yuchoe.pagebound.server.security.BearerTokenFilter;
import io.github.yuchoe.pagebound.server.security.TokenAuthentication;
import io.github.yuchoe.pagebound.server.security.TokenRateLimiter;

/**
 * 웹 보안 (spec/features/F7-common.md 3~4절).
 * SPA용 폼 로그인(성공 204 / 실패 401), 쿠키 CSRF, 익명 읽기 설정(pagebound.anonymous-read).
 * Bearer 토큰(spec/features/F6-mcp-api.md): /mcp는 토큰 전용, 토큰 관리 API는 세션 전용, Bearer 요청은 CSRF 제외.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserStore users, ApiTokenService tokens,
                                            TokenRateLimiter rateLimiter,
                                            @Value("${pagebound.anonymous-read:true}") boolean anonymousRead)
            throws Exception {
        RequestMatcher rawMarkdown = request -> request.getRequestURI().endsWith(".md");
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/mcp", "/mcp/**")
                    .access((a, ctx) -> new AuthorizationDecision(a.get() instanceof TokenAuthentication));
            auth.requestMatchers("/api/v1/me/tokens", "/api/v1/me/tokens/**")
                    .access((a, ctx) -> new AuthorizationDecision(isSession(a.get())));
            auth.requestMatchers("/api/v1/me", "/login", "/logout").permitAll();
            // 관리 기능은 ADMIN (태그 이름 변경·병합·삭제 포함)
            auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/tags/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/tags/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/tags/**").hasRole("ADMIN");
            // 쓰기와 편집용 원문(초안 포함)은 EDITOR/ADMIN
            String[] writers = {"EDITOR", "ADMIN"};
            auth.requestMatchers(HttpMethod.GET, "/api/v1/pages/*/source").hasAnyRole(writers)
                    .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyRole(writers)
                    .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyRole(writers)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyRole(writers)
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyRole(writers);
            if (anonymousRead) {
                auth.requestMatchers(HttpMethod.GET, "/**").permitAll();
            } else {
                // SPA 셸과 정적 리소스만 공개, 데이터(API·첨부·원문)는 로그인 필요
                auth.requestMatchers(HttpMethod.GET, "/api/**", "/p/**").authenticated()
                        .requestMatchers(rawMarkdown).authenticated()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll();
            }
            auth.anyRequest().authenticated();
        });
        http.csrf(csrf -> csrf.spa()
                .ignoringRequestMatchers(BearerTokenFilter::hasBearer, BearerTokenFilter::isMcp));
        http.addFilterBefore(new BearerTokenFilter(tokens, rateLimiter), UsernamePasswordAuthenticationFilter.class);
        http.formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler((request, response, authentication) -> {
                    users.findByEmail(authentication.getName()).ifPresent(u -> users.touchLogin(u.id()));
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                })
                .failureHandler((request, response, exception) ->
                        response.sendError(HttpStatus.UNAUTHORIZED.value()))
                .permitAll());
        http.logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()));
        http.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    private static boolean isSession(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof TokenAuthentication)
                && !(a instanceof AnonymousAuthenticationToken);
    }

    /** 로그인 ID = 이메일, 비밀번호 = Wiki.js에서 이전한 bcrypt($2a$) 해시. */
    @Bean
    UserDetailsService userDetailsService(UserStore users) {
        return email -> users.findByEmail(email)
                .map(u -> User.withUsername(u.email())
                        .password(u.passwordHash())
                        .roles(u.role())
                        .disabled(!u.active())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
