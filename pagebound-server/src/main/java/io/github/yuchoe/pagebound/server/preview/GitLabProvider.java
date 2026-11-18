package io.github.yuchoe.pagebound.server.preview;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitLab API v4 조회 (spec/features/F10-link-preview.md 3절). 설정된 base-url로만 요청한다.
 * 구형 서버 호환: draft↔work_in_progress, detailed_merge_status↔merge_status, head_pipeline↔pipeline.
 */
@Component
public class GitLabProvider {

    public record Fetched(String status, Map<String, Object> data) {
    }

    private static final int MAX_BYTES = 1024 * 1024;
    private static final int SNIPPET_MAX_LINES = 30;
    private static final Logger log = LoggerFactory.getLogger(GitLabProvider.class);

    private final LinkPreviewProperties properties;
    private final JsonMapper json;
    private final HttpClient client;

    public GitLabProvider(LinkPreviewProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 기동 시 버전 확인 (로그만). 토큰 없는 호스트는 미리보기 대상이 아니다. */
    @EventListener(ApplicationReadyEvent.class)
    public void logVersions() {
        Thread.ofVirtual().start(() -> properties.gitlab().forEach(host -> {
            if (!host.hasToken()) {
                log.info("link preview: {} has no token, previews disabled", host.name());
                return;
            }
            try {
                var r = get(host, "/version");
                log.info("link preview: {} status={} version={}", host.name(), r.status,
                        r.body == null ? null : r.body.path("version").asString(""));
            } catch (Exception e) {
                log.warn("link preview: {} version check failed: {}", host.name(), e.getMessage());
            }
        }));
    }

    public Fetched fetch(GitLabUrl url) {
        String project = "/projects/" + encode(url.project());
        try {
            return switch (url.kind()) {
                case "merge_request" -> map(get(url.host(), project + "/merge_requests/" + url.ref()), b -> mergeRequest(url, b));
                case "issue" -> map(get(url.host(), project + "/issues/" + url.ref()), b -> issue(url, b));
                case "commit" -> map(get(url.host(), project + "/repository/commits/" + url.ref()), b -> commit(url, b));
                case "wiki" -> map(get(url.host(), project + "/wikis/" + encode(url.ref())), b -> wiki(url, b));
                case "blob" -> blob(url, project);
                default -> map(get(url.host(), project), b -> project(url, b));
            };
        } catch (IOException e) {
            return new Fetched("error", Map.of("label", url.label()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fetched("error", Map.of("label", url.label()));
        }
    }

    private record Raw(int status, JsonNode body, String text) {
    }

    private Raw get(LinkPreviewProperties.GitLab host, String apiPath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(host.base() + "/api/v4" + apiPath))
                .timeout(properties.timeout()).header("PRIVATE-TOKEN", host.token()).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            String text = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
            return new Raw(response.statusCode(), isJson && response.statusCode() == 200 ? json.readTree(text) : null, text);
        }
    }

    private interface Mapper {
        Map<String, Object> apply(JsonNode body);
    }

    private static Fetched map(Raw raw, Mapper mapper) {
        if (raw.status() == 200 && raw.body() != null) {
            return new Fetched("ok", mapper.apply(raw.body()));
        }
        return new Fetched(failure(raw.status()), null);
    }

    static String failure(int status) {
        return switch (status) {
            case 404 -> "not_found";
            case 401, 403 -> "forbidden";
            default -> "error";
        };
    }

    private static Map<String, Object> base(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", url.label());
        m.put("title", text(b, "title"));
        if (url.noteId() != null) {
            m.put("note", true);
        }
        return m;
    }

    private static Map<String, Object> mergeRequest(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = base(url, b);
        String state = text(b, "state");
        boolean draft = b.path("draft").asBoolean(b.path("work_in_progress").asBoolean(false));
        m.put("state", switch (state == null ? "" : state) {
            case "opened" -> draft ? "draft" : "open";
            case "merged" -> "merged";
            default -> "closed";
        });
        m.put("author", text(b.path("author"), "name"));
        m.put("reviewers", names(b.path("reviewers")));
        m.put("sourceBranch", text(b, "source_branch"));
        m.put("targetBranch", text(b, "target_branch"));
        JsonNode pipeline = b.path("head_pipeline").isObject() ? b.path("head_pipeline") : b.path("pipeline");
        m.put("pipeline", text(pipeline, "status"));
        m.put("mergeStatus", b.hasNonNull("detailed_merge_status") ? text(b, "detailed_merge_status") : text(b, "merge_status"));
        m.put("labels", strings(b.path("labels")));
        m.put("comments", b.path("user_notes_count").asInt(0));
        m.put("updatedAt", text(b, "updated_at"));
        return m;
    }

    private static Map<String, Object> issue(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = base(url, b);
        m.put("state", "opened".equals(text(b, "state")) ? "open" : "closed");
        m.put("assignees", names(b.path("assignees")));
        m.put("labels", strings(b.path("labels")));
        m.put("milestone", text(b.path("milestone"), "title"));
        m.put("dueDate", text(b, "due_date"));
        m.put("comments", b.path("user_notes_count").asInt(0));
        m.put("updatedAt", text(b, "updated_at"));
        return m;
    }

    private static Map<String, Object> commit(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = base(url, b);
        m.put("shortId", text(b, "short_id"));
        m.put("author", text(b, "author_name"));
        m.put("createdAt", text(b, "created_at"));
        return m;
    }

    private static Map<String, Object> project(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", url.label());
        m.put("title", text(b, "name_with_namespace"));
        m.put("description", text(b, "description"));
        m.put("defaultBranch", text(b, "default_branch"));
        m.put("updatedAt", text(b, "last_activity_at"));
        return m;
    }

    private static Map<String, Object> wiki(GitLabUrl url, JsonNode b) {
        Map<String, Object> m = base(url, b);
        m.put("project", url.project());
        return m;
    }

    /** 파일: #L 범위가 있으면 해당 줄 스니펫(최대 30줄). */
    private Fetched blob(GitLabUrl url, String project) throws IOException, InterruptedException {
        Raw raw = get(url.host(), project + "/repository/files/" + encode(url.filePath()) + "/raw?ref=" + encode(url.ref()));
        if (raw.status() != 200) {
            return new Fetched(failure(raw.status()), null);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", url.label());
        m.put("title", url.filePath());
        m.put("ref", url.ref());
        if (url.lineStart() != null) {
            List<String> lines = raw.text().lines().toList();
            int from = Math.max(1, url.lineStart());
            int to = Math.min(Math.min(url.lineEnd(), from + SNIPPET_MAX_LINES - 1), lines.size());
            if (from <= to) {
                m.put("startLine", from);
                m.put("snippet", String.join("\n", lines.subList(from - 1, to)));
            }
        }
        return new Fetched("ok", m);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString();
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("name").asString("")));
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString("")));
        return out;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
