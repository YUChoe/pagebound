package io.github.yuchoe.pagebound.server.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.user.UserStore;

/**
 * 인증 정보 → 사용자 id (리비전·첨부 작성자 기록용).
 */
@Component
public class CurrentUser {

    private final UserStore users;

    public CurrentUser(UserStore users) {
        this.users = users;
    }

    public long id(Authentication authentication) {
        if (authentication == null) {
            throw new WikiException(401, "UNAUTHORIZED", "login required");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new WikiException(401, "UNAUTHORIZED", "unknown user")).id();
    }
}
