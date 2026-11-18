package io.github.yuchoe.pagebound.server.preview;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 외부 URL GET (SSRF 방어, spec/features/F10-link-preview.md 6절).
 * http(s)만, DNS 해석 결과가 사설·루프백·링크로컬 등이면 거부(allow-private-hosts 예외), 리다이렉트마다 재검사.
 */
@Component
public class SafeFetcher {

    public record Response(int status, String contentType, byte[] body, String finalUrl, boolean truncated) {
    }

    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client;
    private final Set<String> allowPrivateHosts;
    private final Duration timeout;

    public SafeFetcher(LinkPreviewProperties properties) {
        this.timeout = properties.timeout();
        this.allowPrivateHosts = properties.opengraph().allowPrivateHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * maxBytes를 넘으면 truncateOk=true일 때 잘라서 반환, 아니면 413 TOO_LARGE.
     * 차단 대상이면 403 BLOCKED_HOST.
     */
    public Response get(String url, int maxBytes, boolean truncateOk, Duration requestTimeout) {
        URI uri = URI.create(url);
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            check(uri);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout == null ? timeout : requestTimeout)
                    .header("User-Agent", "Pagebound-LinkPreview/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new WikiException(502, "FETCH_FAILED", "fetch failed: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WikiException(502, "FETCH_FAILED", "interrupted");
            }
            int status = response.statusCode();
            var location = response.headers().firstValue("Location");
            if (status >= 300 && status < 400 && location.isPresent()) {
                close(response.body());
                uri = uri.resolve(location.get());
                continue;
            }
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(maxBytes + 1);
                boolean truncated = body.length > maxBytes;
                if (truncated && !truncateOk) {
                    throw new WikiException(413, "TOO_LARGE", "file exceeds " + maxBytes + " bytes");
                }
                return new Response(status, response.headers().firstValue("Content-Type").orElse(""),
                        truncated ? Arrays.copyOf(body, maxBytes) : body, uri.toString(), truncated);
            } catch (IOException e) {
                throw new WikiException(502, "FETCH_FAILED", "read failed: " + e.getClass().getSimpleName());
            }
        }
        throw new WikiException(502, "FETCH_FAILED", "too many redirects");
    }

    void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!scheme.equals("http") && !scheme.equals("https") || host == null) {
            throw new WikiException(400, "BAD_URL", "only http(s) URLs are allowed");
        }
        if (allowPrivateHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new WikiException(502, "FETCH_FAILED", "unknown host");
        }
        for (InetAddress a : addresses) {
            if (isBlocked(a)) {
                throw new WikiException(403, "BLOCKED_HOST", "private or local address is not allowed: " + host);
            }
        }
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // fc00::/7 고유 로컬
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0 || first == 100 && second >= 64 && second <= 127 // 0.0.0.0/8, 100.64.0.0/10 CGNAT
                || first == 198 && (second == 18 || second == 19) || first >= 240; // 벤치마크, 예약
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 리다이렉트 응답 본문은 버린다
        }
    }
}
