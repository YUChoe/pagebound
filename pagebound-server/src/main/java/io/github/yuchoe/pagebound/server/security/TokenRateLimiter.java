package io.github.yuchoe.pagebound.server.security;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;

/**
 * 토큰당 분당 호출 제한 (고정 1분 창). REST(Bearer)와 MCP 도구 호출이 같은 한도를 공유한다.
 * 한도는 런타임 설정 mcp.rate-limit-per-minute (기본 60).
 */
@Component
public class TokenRateLimiter {

    private record Window(long minute, int count) {
    }

    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final RuntimeSettings settings;

    public TokenRateLimiter(RuntimeSettings settings) {
        this.settings = settings;
    }

    public int limit() {
        return settings.mcpRateLimitPerMinute();
    }

    public boolean tryAcquire(long tokenId) {
        long minute = System.currentTimeMillis() / 60_000;
        int limit = limit();
        boolean[] allowed = {false};
        windows.compute(tokenId, (id, w) -> {
            Window current = w == null || w.minute() != minute ? new Window(minute, 0) : w;
            if (current.count() >= limit) {
                return current;
            }
            allowed[0] = true;
            return new Window(minute, current.count() + 1);
        });
        return allowed[0];
    }
}
