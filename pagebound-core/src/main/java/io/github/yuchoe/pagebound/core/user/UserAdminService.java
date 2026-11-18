package io.github.yuchoe.pagebound.core.user;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.audit.AuditLog;

/**
 * 사용자 관리 (spec/features/F12-admin.md 5절). 삭제는 없고 비활성화로 대체한다.
 * 비밀번호 해시는 호출 측(PasswordEncoder)에서 넘긴다. 감사 detail에 비밀번호를 넣지 않는다.
 */
@Service
public class UserAdminService {

    public record UserRow(long id, String email, String name, String role, boolean active, String lastLoginAt,
                          int tokenCount, int editCount, boolean mustChangePassword) {
    }

    /** temporaryPassword: 자동 생성했을 때만 1회 반환. */
    public record Created(long id, String email, String temporaryPassword) {
    }

    private static final Set<String> ROLES = Set.of("ADMIN", "EDITOR", "VIEWER");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public UserAdminService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<UserRow> list() {
        return jdbc.sql("""
                SELECT u.id, u.email, u.name, u.role, u.is_active, u.last_login_at,
                       (SELECT count(*) FROM api_tokens t WHERE t.user_id = u.id AND t.revoked_at IS NULL),
                       (SELECT count(*) FROM page_revisions r WHERE r.author_id = u.id), u.must_change_password
                FROM users u ORDER BY u.is_active DESC, u.name COLLATE NOCASE""")
                .query((rs, n) -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5) == 1, rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9) == 1))
                .list();
    }

    /** password가 비어 있으면 16자 임시 비밀번호를 만들어 반환한다. 최소 8자. */
    @Transactional
    public Created create(String email, String name, String role, String password, UnaryOperator<String> hasher,
                          long actorId) {
        String mail = email == null ? "" : email.strip();
        if (!mail.matches("[^@\\s]+@[^@\\s]+")) {
            throw WikiException.badRequest("invalid email");
        }
        if (name == null || name.isBlank()) {
            throw WikiException.badRequest("name is required");
        }
        requireRole(role);
        if (jdbc.sql("SELECT count(*) FROM users WHERE email = ?").param(mail).query(Integer.class).single() > 0) {
            throw new WikiException(409, "EMAIL_EXISTS", "email already exists: " + mail);
        }
        boolean generated = password == null || password.isEmpty();
        String plain = generated ? generatePassword() : password;
        requirePasswordPolicy(plain);
        String now = Instant.now().toString();
        long id = jdbc.sql("""
                INSERT INTO users (email, name, password_hash, role, is_active, must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 1, ?, ?) RETURNING id""")
                .params(mail, name.strip(), hasher.apply(plain), role, now, now).query(Long.class).single();
        audit.record(actorId, "USER_CREATE", "user", id, Map.of("email", mail, "role", role));
        return new Created(id, mail, generated ? plain : null);
    }

    /** 역할·활성 상태 변경. 마지막 활성 ADMIN 보호(409 LAST_ADMIN), 자기 자신 강등·비활성화 금지(409 SELF_CHANGE). */
    @Transactional
    public void update(long id, String role, Boolean active, long actorId) {
        record Current(String role, boolean active) {
        }
        Current current = jdbc.sql("SELECT role, is_active FROM users WHERE id = ?").param(id)
                .query((rs, n) -> new Current(rs.getString(1), rs.getInt(2) == 1)).optional()
                .orElseThrow(() -> WikiException.notFound("user " + id));
        String newRole = role == null ? current.role() : role;
        boolean newActive = active == null ? current.active() : active;
        requireRole(newRole);
        boolean losesAdmin = "ADMIN".equals(current.role()) && current.active()
                && (!"ADMIN".equals(newRole) || !newActive);
        if (losesAdmin && id == actorId) {
            throw new WikiException(409, "SELF_CHANGE", "you cannot demote or deactivate yourself");
        }
        if (losesAdmin && activeAdmins() <= 1) {
            throw new WikiException(409, "LAST_ADMIN", "the last active admin cannot be demoted or deactivated");
        }
        if (!newActive && id == actorId) {
            throw new WikiException(409, "SELF_CHANGE", "you cannot deactivate yourself");
        }
        jdbc.sql("UPDATE users SET role = ?, is_active = ?, updated_at = ? WHERE id = ?")
                .params(newRole, newActive ? 1 : 0, Instant.now().toString(), id).update();
        if (!newRole.equals(current.role())) {
            audit.record(actorId, "USER_ROLE", "user", id, Map.of("from", current.role(), "to", newRole));
        }
        if (newActive != current.active()) {
            audit.record(actorId, newActive ? "USER_ACTIVATE" : "USER_DEACTIVATE", "user", id, Map.of());
        }
    }

    /** 임시 비밀번호 발급 (다음 로그인 시 변경 강제). */
    @Transactional
    public String resetPassword(long id, UnaryOperator<String> hasher, long actorId) {
        String plain = generatePassword();
        int updated = jdbc.sql("UPDATE users SET password_hash = ?, must_change_password = 1, updated_at = ? WHERE id = ?")
                .params(hasher.apply(plain), Instant.now().toString(), id).update();
        if (updated == 0) {
            throw WikiException.notFound("user " + id);
        }
        audit.record(actorId, "USER_PASSWORD_RESET", "user", id, Map.of());
        return plain;
    }

    /** 본인 비밀번호 변경 (현재 비밀번호 확인은 호출 측). */
    public void changeOwnPassword(long id, String newPassword, UnaryOperator<String> hasher) {
        requirePasswordPolicy(newPassword);
        jdbc.sql("UPDATE users SET password_hash = ?, must_change_password = 0, updated_at = ? WHERE id = ?")
                .params(hasher.apply(newPassword), Instant.now().toString(), id).update();
    }

    public int activeAdmins() {
        return jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN' AND is_active = 1").query(Integer.class).single();
    }

    private static void requireRole(String role) {
        if (!ROLES.contains(role)) {
            throw WikiException.badRequest("role must be ADMIN, EDITOR or VIEWER");
        }
    }

    static void requirePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw WikiException.badRequest("password must be at least 8 characters");
        }
    }

    static String generatePassword() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
