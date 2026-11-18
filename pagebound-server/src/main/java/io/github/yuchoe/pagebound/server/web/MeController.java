package io.github.yuchoe.pagebound.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.user.UserAdminService;
import io.github.yuchoe.pagebound.core.user.UserStore;

/**
 * 본인 비밀번호 변경 (spec/features/F7-common.md 4절). 웹 세션 전용 (SecurityConfig).
 */
@RestController
public class MeController {

    public record PasswordRequest(String current, String next) {
    }

    private final UserStore users;
    private final UserAdminService admin;
    private final PasswordEncoder encoder;

    public MeController(UserStore users, UserAdminService admin, PasswordEncoder encoder) {
        this.users = users;
        this.admin = admin;
        this.encoder = encoder;
    }

    @PostMapping("/api/v1/me/password")
    public ResponseEntity<Void> changePassword(@RequestBody PasswordRequest r, Authentication auth) {
        var user = users.findByEmail(auth.getName()).orElseThrow(() -> new WikiException(401, "UNAUTHORIZED", "unknown user"));
        if (r.current() == null || !encoder.matches(r.current(), user.passwordHash())) {
            throw new WikiException(400, "WRONG_PASSWORD", "current password does not match");
        }
        admin.changeOwnPassword(user.id(), r.next(), encoder::encode);
        return ResponseEntity.noContent().build();
    }
}
