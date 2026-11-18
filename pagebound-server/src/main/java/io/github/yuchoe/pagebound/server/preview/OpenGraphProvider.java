package io.github.yuchoe.pagebound.server.preview;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.WikiException;

/**
 * 일반 웹 페이지 OpenGraph/title 수집 (spec/features/F10-link-preview.md 3절 일반 웹, 6절 응답 제한).
 * 본문 최대 1MB, HTML은 head만 정규식으로 읽는다.
 */
@Component
public class OpenGraphProvider {

    private static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ICON = Pattern.compile("<link\\s[^>]*rel\\s*=\\s*[\"']?(?:shortcut )?icon[\"']?[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private final SafeFetcher fetcher;

    public OpenGraphProvider(SafeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public GitLabProvider.Fetched fetch(String url) {
        SafeFetcher.Response response;
        try {
            response = fetcher.get(url, MAX_BYTES, true, null);
        } catch (WikiException e) {
            return new GitLabProvider.Fetched("BLOCKED_HOST".equals(e.code()) ? "forbidden" : "error", null);
        }
        if (response.status() != 200) {
            return new GitLabProvider.Fetched(GitLabProvider.failure(response.status()), null);
        }
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("html")) {
            return new GitLabProvider.Fetched("error", null);
        }
        return new GitLabProvider.Fetched("ok", parse(new String(response.body(), StandardCharsets.UTF_8), response.finalUrl()));
    }

    static Map<String, Object> parse(String html, String pageUrl) {
        int headEnd = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        String head = headEnd > 0 ? html.substring(0, headEnd) : html;
        Map<String, String> meta = new LinkedHashMap<>();
        Matcher m = META.matcher(head);
        while (m.find()) {
            Map<String, String> attrs = attributes(m.group());
            String key = attrs.getOrDefault("property", attrs.get("name"));
            if (key != null && attrs.containsKey("content")) {
                meta.putIfAbsent(key.toLowerCase(Locale.ROOT), attrs.get("content"));
            }
        }
        URI base = URI.create(pageUrl);
        Map<String, Object> data = new LinkedHashMap<>();
        Matcher title = TITLE.matcher(head);
        data.put("title", first(meta.get("og:title"), title.find() ? title.group(1) : null));
        data.put("description", first(meta.get("og:description"), meta.get("description")));
        data.put("siteName", first(meta.get("og:site_name"), base.getHost()));
        data.put("label", data.get("siteName"));
        data.put("image", absolute(base, meta.get("og:image")));
        Matcher icon = ICON.matcher(head);
        data.put("favicon", absolute(base, icon.find() ? attributes(icon.group()).get("href") : "/favicon.ico"));
        data.values().removeIf(v -> v == null);
        return data;
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> attrs = new LinkedHashMap<>();
        Matcher a = ATTR.matcher(tag);
        while (a.find()) {
            String v = a.group(2);
            if (v.startsWith("\"") || v.startsWith("'")) {
                v = v.substring(1, v.length() - 1);
            }
            attrs.put(a.group(1).toLowerCase(Locale.ROOT), unescape(v));
        }
        return attrs;
    }

    private static String first(String a, String b) {
        String v = a != null && !a.isBlank() ? a : b;
        return v == null ? null : unescape(v.strip().replaceAll("\\s+", " "));
    }

    private static String absolute(URI base, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            String url = base.resolve(href.strip()).toString();
            return url.startsWith("http://") || url.startsWith("https://") ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }
}
