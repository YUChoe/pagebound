package io.github.yuchoe.pagebound.core.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 사용자 조회. 로그인 ID는 이메일(대소문자 무시).
 */
@Repository
public class UserStore {

    public record UserAccount(long id, String email, String name, String passwordHash, String role, boolean active) {
    }

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, name, password_hash, role, is_active FROM users WHERE email = ?")
                .param(email)
                .query((rs, n) -> new UserAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6) == 1))
                .optional();
    }

    public void touchLogin(long userId) {
        jdbc.sql("UPDATE users SET last_login_at = ? WHERE id = ?").params(Instant.now().toString(), userId).update();
    }
}
