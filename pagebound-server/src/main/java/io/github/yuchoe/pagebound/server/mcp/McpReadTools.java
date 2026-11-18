package io.github.yuchoe.pagebound.server.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;
import io.github.yuchoe.pagebound.core.page.HistoryService;
import io.github.yuchoe.pagebound.core.page.PageReadService;
import io.github.yuchoe.pagebound.core.page.PageStore;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import io.github.yuchoe.pagebound.core.search.SearchService;
import io.github.yuchoe.pagebound.core.tag.TagStore;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Args;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Caller;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Schema;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP 읽기 도구 10개 (spec/features/F6-mcp-api.md 3.1절).
 */
@Component
public class McpReadTools {

    private static final int TEXT_ATTACHMENT_LIMIT = 1024 * 1024;
    private static final int IMAGE_ATTACHMENT_LIMIT = 5 * 1024 * 1024;

    private final SearchService search;
    private final PageReadService pages;
    private final PageStore store;
    private final HistoryService history;
    private final TagStore tags;
    private final AttachmentStorage storage;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public McpReadTools(SearchService search, PageReadService pages, PageStore store, HistoryService history,
                        TagStore tags, AttachmentStorage storage) {
        this.search = search;
        this.pages = pages;
        this.store = store;
        this.history = history;
        this.tags = tags;
        this.storage = storage;
    }

    public List<McpTool> tools() {
        return List.of(
                new McpTool("search_pages", """
                        문서 검색. 문법: 공백=AND, "구문", -제외어, tag:태그, path:경로(하위 트리), in:code. \
                        결과의 section은 일치한 섹션 번호(get_page section에 사용).""",
                        Schema.object().str("query", "검색어", true).strArray("tags", "태그 필터 (AND)")
                                .str("path", "하위 트리 필터", false).integer("limit", "최대 결과 수 (기본 10, 최대 50)", false)
                                .build(), false, this::searchPages),
                new McpTool("get_page", """
                        문서 원문(마크다운) 조회. section은 번호(2.1) 또는 헤딩 텍스트. max_chars(기본 40000)를 넘으면 \
                        잘린 본문 + outline + truncated=true. 반환 version을 update_page의 base_version으로 사용.""",
                        Schema.object().str("path", "문서 경로", true).str("section", "섹션 번호 또는 헤딩 텍스트", false)
                                .integer("max_chars", "최대 문자 수 (기본 40000)", false).build(), false, this::getPage),
                new McpTool("get_outline", "번호 매긴 헤딩 목록 + 섹션별 문자 수. 큰 문서는 이걸로 섹션을 고른다.",
                        pathOnly(), false, this::getOutline),
                new McpTool("list_children", "하위 문서 목록. path가 없으면 최상위.",
                        Schema.object().str("path", "상위 문서 경로 (없으면 루트)", false)
                                .integer("depth", "펼칠 깊이 (기본 1, 최대 5)", false).build(), false, this::listChildren),
                new McpTool("list_tags", "태그와 사용 수.", Schema.object().build(), false,
                        (a, c) -> tags.list("", 1000, false).stream().map(t -> Map.of("name", t.name(), "count", t.count()))
                                .toList()),
                new McpTool("recent_changes", "최근 변경 (리비전 단위). source=mcp는 에이전트 편집.",
                        Schema.object().str("since", "ISO-8601 시각 이후만 (예: 2026-11-01T00:00:00Z)", false)
                                .integer("limit", "최대 수 (기본 20, 최대 100)", false).build(), false,
                        (a, c) -> pages.recent(a.optStr("since"), a.integer("limit", 20, 1, 100))),
                new McpTool("get_backlinks", "이 문서를 [[링크]]로 참조하는 문서 목록.", pathOnly(), false,
                        (a, c) -> history.backlinks(path(a))),
                new McpTool("get_history", "문서 버전 이력 (최신순). tokenName이 있으면 에이전트 편집.",
                        Schema.object().str("path", "문서 경로", true).integer("limit", "최대 수 (기본 20, 최대 200)", false)
                                .build(), false, (a, c) -> history.history(path(a), a.integer("limit", 20, 1, 200))),
                new McpTool("get_revision", "특정 버전의 마크다운.",
                        Schema.object().str("path", "문서 경로", true).integer("version", "버전", true).build(), false,
                        (a, c) -> history.revision(path(a), a.requiredInt("version"))),
                new McpTool("get_attachment", """
                        문서 첨부 조회. 이미지는 image content, 텍스트 계열(1MB 이하)은 본문, 그 외는 메타데이터와 다운로드 URL.""",
                        Schema.object().str("path", "문서 경로", true).str("filename", "첨부 파일명", true).build(), false,
                        this::getAttachment));
    }

    private static Map<String, Object> pathOnly() {
        return Schema.object().str("path", "문서 경로", true).build();
    }

    static String path(Args args) {
        return WikiPaths.normalize(args.str("path"));
    }

    /** 활성 문서 id. 이전 경로(alias)면 현재 경로를 알려 준다. */
    long requireId(String path) {
        return store.findActiveIdByPath(path).orElseThrow(() -> pages.aliasTarget(path)
                .map(target -> new WikiException(404, "NOT_FOUND", "page moved: " + path + " -> " + target,
                        Map.of("movedTo", target)))
                .orElseGet(() -> WikiException.notFound("page " + path)));
    }

    private Object searchPages(Args a, Caller c) {
        var result = search.search(a.str("query"), a.strList("tags"), a.optStr("path"), "relevance", 1,
                a.integer("limit", 10, 1, 50));
        List<Map<String, Object>> items = result.items().stream().map(hit -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", hit.path());
            store.findActiveIdByPath(hit.path()).ifPresent(id -> item.put("permalink", c.permalink(id)));
            item.put("title", hit.title());
            item.put("tags", hit.tags());
            item.put("snippets", hit.snippets().stream().map(s -> unmark(s.text())).toList());
            if (hit.section() != null) {
                item.put("section", hit.section().number() + " " + hit.section().title());
            }
            item.put("updatedAt", hit.updatedAt());
            return item;
        }).toList();
        return Map.of("total", result.total(), "items", items);
    }

    private static String unmark(String text) {
        return text.replace(String.valueOf(SearchService.MARK_START), "").replace(String.valueOf(SearchService.MARK_END), "");
    }

    private Object getPage(Args a, Caller c) {
        String path = path(a);
        requireId(path);
        var view = pages.view(path).orElseThrow(() -> WikiException.notFound("page " + path));
        String content = view.content();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", view.id());
        out.put("path", view.path());
        out.put("title", view.title());
        out.put("permalink", c.permalink(view.id()));
        out.put("version", view.version());
        out.put("updatedAt", view.updatedAt());
        out.put("updatedBy", view.updatedBy());
        out.put("tags", view.tags());
        if (a.has("section")) {
            MarkdownAnalyzer.Section s = findSection(content, a.str("section"));
            out.put("section", Map.of("index", s.index(), "number", s.number(), "title", s.title()));
            content = content.substring(s.start(), s.end());
        }
        int max = a.integer("max_chars", 40_000, 1_000, 200_000);
        boolean truncated = content.length() > max;
        out.put("content", truncated ? content.substring(0, max) : content);
        out.put("truncated", truncated);
        if (truncated) {
            out.put("outline", outline(view.content()));
        }
        out.put("attachments", view.attachments().stream()
                .map(f -> Map.of("filename", f.filename(), "mime", f.mime(), "size", f.size(), "url", f.url())).toList());
        return out;
    }

    /** 섹션 번호("2.1", "2.1.") 또는 헤딩 텍스트(대소문자 무시)로 찾는다. */
    MarkdownAnalyzer.Section findSection(String content, String key) {
        String k = key.strip();
        String number = k.endsWith(".") ? k : k + ".";
        List<MarkdownAnalyzer.Section> sections = analyzer.sections(content);
        return sections.stream().filter(s -> s.number().equals(number)).findFirst()
                .or(() -> sections.stream().filter(s -> s.title().equalsIgnoreCase(k)).findFirst())
                .orElseThrow(() -> new WikiException(404, "SECTION_NOT_FOUND", "section not found: " + key));
    }

    private List<Map<String, Object>> outline(String content) {
        return analyzer.sections(content).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", s.number());
            m.put("level", s.level());
            m.put("title", s.title());
            m.put("chars", s.end() - s.start());
            return m;
        }).toList();
    }

    private Object getOutline(Args a, Caller c) {
        String path = path(a);
        requireId(path);
        String content = pages.content(path).orElseThrow(() -> WikiException.notFound("page " + path));
        return Map.of("path", path, "totalChars", content.length(), "sections", outline(content));
    }

    private Object listChildren(Args a, Caller c) {
        String raw = a.optStr("path");
        Long parentId = raw == null || WikiPaths.normalize(raw).isEmpty() ? null : requireId(WikiPaths.normalize(raw));
        return pages.tree(parentId, a.integer("depth", 1, 1, 5));
    }

    private Object getAttachment(Args a, Caller c) {
        String path = path(a);
        long pageId = requireId(path);
        var file = pages.attachment(pageId, a.str("filename"))
                .orElseThrow(() -> WikiException.notFound("attachment " + a.str("filename")));
        String url = c.baseUrl() + AttachmentStorage.url(pageId, file.filename());
        String meta = "%s (%s, %d bytes) %s".formatted(file.filename(), file.mime(), file.size(), url);
        String mime = file.mime() == null ? "" : file.mime();
        if (mime.startsWith("image/") && !mime.contains("svg") && file.size() <= IMAGE_ATTACHMENT_LIMIT) {
            String data = Base64.getEncoder().encodeToString(storage.read(pageId, file.attachmentId()));
            return McpSchema.CallToolResult.builder()
                    .addContent(McpSchema.ImageContent.builder(data, mime).build())
                    .addTextContent(meta).isError(false).build();
        }
        if (isText(mime) && file.size() <= TEXT_ATTACHMENT_LIMIT) {
            String text = new String(storage.read(pageId, file.attachmentId()), StandardCharsets.UTF_8);
            return McpSchema.CallToolResult.builder().addTextContent(meta).addTextContent(text).isError(false).build();
        }
        return Map.of("filename", file.filename(), "mime", mime, "size", file.size(), "url", url);
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("yaml")
                || mime.contains("javascript") || mime.contains("svg") || mime.equals("application/x-sh");
    }
}
