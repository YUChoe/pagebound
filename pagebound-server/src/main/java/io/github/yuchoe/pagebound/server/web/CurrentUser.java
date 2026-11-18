package io.github.yuchoe.pagebound.server.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.Actor;
import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.user.UserStore;
import io.github.yuchoe.pagebound.server.security.TokenAuthentication;

/**
 * 인증 정보 → 사용자 id·쓰기 주체 (리비전·첨부 작성자 기록용).
 */
@Component
public class CurrentUser {

    private final UserStore users;

    public CurrentUser(UserStore users) {
        this.users = users;
    }

    public long id(Authentication authentication) {
        if (authentication instanceof TokenAuthentication token) {
            return token.token().userId();
        }
        if (authentication == null) {
            throw new WikiException(401, "UNAUTHORIZED", "login required");
        }
        return users.findByEmail(authentication.getName())
                .orElseThrow(() -> new WikiException(401, "UNAUTHORIZED", "unknown user")).id();
    }

    /** 웹 세션이면 source=web, Bearer 토큰(REST)이면 source=api + token_id. */
    public Actor actor(Authentication authentication) {
        if (authentication instanceof TokenAuthentication token) {
            return new Actor(token.token().userId(), "api", token.token().tokenId());
        }
        return Actor.web(id(authentication));
    }
}
