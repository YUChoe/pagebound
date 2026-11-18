package io.github.yuchoe.pagebound.server.security;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 토큰당 분당 호출 제한 (고정 1분 창). REST(Bearer)와 MCP 도구 호출이 같은 한도를 공유한다.
 */
@Component
public class TokenRateLimiter {

    public static final int PER_MINUTE = 60;

    private record Window(long minute, int count) {
    }

    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(long tokenId) {
        long minute = System.currentTimeMillis() / 60_000;
        boolean[] allowed = {false};
        windows.compute(tokenId, (id, w) -> {
            Window current = w == null || w.minute() != minute ? new Window(minute, 0) : w;
            if (current.count() >= PER_MINUTE) {
                return current;
            }
            allowed[0] = true;
            return new Window(minute, current.count() + 1);
        });
        return allowed[0];
    }
}
