package io.github.yuchoe.pagebound.server.preview;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.yuchoe.pagebound.core.WikiException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 링크 미리보기 (spec/features/F10-link-preview.md 5절): 캐시(link_previews) → 만료면 기존 값 반환 + 백그라운드 갱신,
 * 없으면 프로바이더 호출(호스트당 동시 N건). 토큰 없는 GitLab 호스트·미지원 URL은 unsupported (캐시 안 함).
 */
@Service
public class LinkPreviewService {

    /** status: ok, not_found, forbidden, error, unsupported */
    public record Preview(String url, String kind, String status, Map<String, Object> data) {
    }

    private record Target(String key, String provider, String kind, String host, GitLabUrl gitlab, String web) {
    }

    private record Cached(String kind, String status, Map<String, Object> data, Instant expiresAt) {
    }

    static final int MAX_BATCH = 100;
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern TRACKING = Pattern.compile("(?<=[?&])(utm_[^=&]*|fbclid|gclid)=[^&#]*&?");
    private static final Duration BATCH_DEADLINE = Duration.ofSeconds(8);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final LinkPreviewProperties properties;
    private final GitLabProvider gitlab;
    private final OpenGraphProvider openGraph;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Semaphore> perHost = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public LinkPreviewService(JdbcClient jdbc, JsonMapper json, LinkPreviewProperties properties,
                              GitLabProvider gitlab, OpenGraphProvider openGraph) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.gitlab = gitlab;
        this.openGraph = openGraph;
    }

    public List<Preview> previews(List<String> urls) {
        if (urls == null) {
            throw WikiException.badRequest("urls is required");
        }
        if (urls.size() > MAX_BATCH) {
            throw WikiException.badRequest("at most " + MAX_BATCH + " urls");
        }
        long deadline = System.nanoTime() + BATCH_DEADLINE.toNanos();
        List<String> distinct = urls.stream().distinct().toList();
        List<Future<Preview>> futures = new ArrayList<>();
        for (String url : distinct) {
            futures.add(executor.submit(() -> preview(url, false)));
        }
        List<Preview> out = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                out.add(new Preview(distinct.get(i), null, "error", null));
            }
        }
        return out;
    }

    public Preview refresh(String url) {
        return preview(url, true);
    }

    private Preview preview(String url, boolean force) {
        Optional<Target> found = target(url);
        if (found.isEmpty()) {
            return new Preview(url, null, "unsupported", null);
        }
        Target t = found.get();
        if (!force) {
            Optional<Cached> cached = cached(t.key());
            if (cached.isPresent()) {
                Cached c = cached.get();
                if (c.expiresAt().isBefore(Instant.now()) && refreshing.add(t.key())) {
                    executor.submit(() -> {
                        try {
                            fetchAndStore(t);
                        } finally {
                            refreshing.remove(t.key());
                        }
                    });
                }
                return new Preview(url, c.kind(), c.status(), c.data());
            }
        }
        return toPreview(url, t, fetchAndStore(t));
    }

    private Preview toPreview(String url, Target t, GitLabProvider.Fetched f) {
        Map<String, Object> data = f.data();
        if (data == null && t.gitlab() != null) {
            data = Map.of("label", t.gitlab().label());
        }
        return new Preview(url, t.kind(), f.status(), data);
    }

    private GitLabProvider.Fetched fetchAndStore(Target t) {
        Semaphore semaphore = perHost.computeIfAbsent(t.host(), h -> new Semaphore(properties.perHostConcurrency()));
        GitLabProvider.Fetched fetched;
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitLabProvider.Fetched("error", null);
        }
        try {
            fetched = t.gitlab() != null ? gitlab.fetch(t.gitlab()) : openGraph.fetch(t.web());
        } finally {
            semaphore.release();
        }
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT OR REPLACE INTO link_previews (url_key, provider, kind, status, data, fetched_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(t.key(), t.provider(), t.kind(), fetched.status(),
                        fetched.data() == null ? null : json.writeValueAsString(fetched.data()), now.toString(),
                        now.plus(ttl(t, fetched)).toString())
                .update();
        return fetched;
    }

    /** TTL (5절 표). */
    static Duration ttl(Target t, GitLabProvider.Fetched f) {
        if (!"ok".equals(f.status())) {
            return Duration.ofMinutes(5);
        }
        Object state = f.data() == null ? null : f.data().get("state");
        return switch (t.kind()) {
            case "merge_request", "issue" -> "open".equals(state) || "draft".equals(state)
                    ? Duration.ofMinutes(10) : Duration.ofHours(24);
            case "commit" -> Duration.ofDays(30);
            case "blob" -> SHA.matcher(t.gitlab().ref()).matches() ? Duration.ofDays(30) : Duration.ofHours(24);
            default -> Duration.ofHours(24);
        };
    }

    private Optional<Cached> cached(String key) {
        return jdbc.sql("SELECT kind, status, data, expires_at FROM link_previews WHERE url_key = ?").param(key)
                .query((rs, n) -> new Cached(rs.getString(1), rs.getString(2), readData(rs.getString(3)),
                        Instant.parse(rs.getString(4))))
                .optional();
    }

    private Map<String, Object> readData(String text) {
        return text == null ? null : json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** GitLab(설정된 호스트 + 토큰) 또는 일반 웹(OpenGraph 활성 시). */
    private Optional<Target> target(String raw) {
        if (raw == null || !(raw.startsWith("http://") || raw.startsWith("https://")) || raw.length() > 2000) {
            return Optional.empty();
        }
        String url = TRACKING.matcher(raw).replaceAll("").replaceAll("[?&]$", "").replace("?#", "#");
        boolean gitlabHost = properties.gitlab().stream().anyMatch(h -> url.startsWith(h.base() + "/"));
        if (gitlabHost) {
            return GitLabUrl.parse(url, properties.gitlab())
                    .filter(g -> g.host().hasToken())
                    .map(g -> new Target(g.canonical(), "gitlab:" + g.host().name(), g.kind(), g.host().base(), g, null));
        }
        if (!properties.opengraph().enabled()) {
            return Optional.empty();
        }
        String key = url.replaceAll("#.*$", "");
        String host;
        try {
            host = URI.create(key).getHost();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return host == null ? Optional.empty() : Optional.of(new Target(key, "opengraph", "web", host, null, key));
    }
}
