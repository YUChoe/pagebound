package io.github.yuchoe.pagebound.core.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.audit.AuditLog;

/**
 * API 토큰 (spec/features/F6-mcp-api.md 2절). 원문은 발급 시 1회만 반환하고 DB에는 SHA-256만 저장한다.
 */
@Service
public class ApiTokenService {

    public record Issued(long id, String name, String scope, String expiresAt, String token) {
    }

    public record TokenInfo(long id, long userId, String userName, String name, String scope, String createdAt,
                            String expiresAt, String lastUsedAt, String revokedAt) {
    }

    /** 인증된 토큰. role은 사용자 role (실제 권한은 scope와 조합해 결정). */
    public record Principal(long tokenId, String tokenName, String scope, long userId, String email, String role) {
    }

    private static final String PREFIX = "pb_";
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 43자 × log2(62) ≈ 256비트 (32바이트) */
    private static final int LENGTH = 43;
    private static final Set<Integer> EXPIRY_DAYS = Set.of(30, 90);
    private static final long TOUCH_INTERVAL_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public ApiTokenService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** expiresInDays: 30, 90 또는 null(무기한). */
    public Issued issue(long userId, String name, String scope, Integer expiresInDays) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw WikiException.badRequest("name must be 1..100 characters");
        }
        if (!"read".equals(scope) && !"write".equals(scope)) {
            throw WikiException.badRequest("scope must be read or write");
        }
        if (expiresInDays != null && !EXPIRY_DAYS.contains(expiresInDays)) {
            throw WikiException.badRequest("expiresInDays must be 30, 90 or null");
        }
        Instant now = Instant.now();
        String expiresAt = expiresInDays == null ? null : now.plus(expiresInDays, ChronoUnit.DAYS).toString();
        String token = generate();
        long id = jdbc.sql("""
                INSERT INTO api_tokens (user_id, name, token_hash, scope, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(userId, trimmed, hash(token), scope, now.toString(), expiresAt)
                .query(Long.class).single();
        audit.record(userId, "TOKEN_ISSUE", "token", id, Map.of("name", trimmed, "scope", scope));
        return new Issued(id, trimmed, scope, expiresAt, token);
    }

    /** userId가 null이면 전체 (관리자). 최근 발급 순. */
    public List<TokenInfo> list(Long userId) {
        var spec = jdbc.sql("""
                SELECT t.id, t.user_id, u.name, t.name, t.scope, t.created_at, t.expires_at, t.last_used_at, t.revoked_at
                FROM api_tokens t JOIN users u ON u.id = t.user_id
                %s ORDER BY t.id DESC""".formatted(userId == null ? "" : "WHERE t.user_id = :user"));
        if (userId != null) {
            spec = spec.param("user", userId);
        }
        return spec.query((rs, n) -> new TokenInfo(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))).list();
    }

    /** ownerId가 null이면 관리자 강제 폐기. 본인 토큰이 아니면 404. */
    public void revoke(long tokenId, Long ownerId, long actorId) {
        int updated = jdbc.sql("""
                        UPDATE api_tokens SET revoked_at = ?
                        WHERE id = ? AND revoked_at IS NULL AND (? IS NULL OR user_id = ?)""")
                .params(Instant.now().toString(), tokenId, ownerId, ownerId)
                .update();
        if (updated == 0) {
            throw WikiException.notFound("token");
        }
        audit.record(actorId, "TOKEN_REVOKE", "token", tokenId, Map.of("forced", ownerId == null));
    }

    /** 폐기·만료 토큰, 비활성 사용자는 빈 값. 마지막 사용 시각은 1분 간격으로만 갱신한다. */
    public Optional<Principal> authenticate(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<Principal> principal = jdbc.sql("""
                SELECT t.id, t.name, t.scope, u.id, u.email, u.role
                FROM api_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > ?)
                  AND u.is_active = 1""")
                .params(hash(raw), now.toString())
                .query((rs, n) -> new Principal(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6)))
                .optional();
        principal.ifPresent(p -> jdbc.sql(
                        "UPDATE api_tokens SET last_used_at = ? WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)")
                .params(now.toString(), p.tokenId(), now.minusSeconds(TOUCH_INTERVAL_SECONDS).toString())
                .update());
        return principal;
    }

    static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
