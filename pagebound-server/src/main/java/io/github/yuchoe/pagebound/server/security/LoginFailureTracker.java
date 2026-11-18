package io.github.yuchoe.pagebound.server.security;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.audit.AuditLog;

/**
 * 로그인 연속 실패 감사 (spec/features/F12-admin.md 12절): 같은 ID로 5회 연속 실패하면 감사 로그 1건.
 */
@Component
public class LoginFailureTracker {

    static final int THRESHOLD = 5;

    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final AuditLog audit;

    public LoginFailureTracker(AuditLog audit) {
        this.audit = audit;
    }

    public void failed(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = username.strip().toLowerCase(Locale.ROOT);
        int count = failures.merge(key, 1, Integer::sum);
        if (count % THRESHOLD == 0) {
            audit.record(null, "LOGIN_FAILED", "user", key, Map.of("consecutive", count));
        }
    }

    public void succeeded(String username) {
        if (username != null) {
            failures.remove(username.strip().toLowerCase(Locale.ROOT));
        }
    }
}
