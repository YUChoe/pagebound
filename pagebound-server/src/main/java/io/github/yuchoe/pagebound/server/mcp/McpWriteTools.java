package io.github.yuchoe.pagebound.server.mcp;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.attachment.AttachmentService;
import io.github.yuchoe.pagebound.core.page.PageTreeService;
import io.github.yuchoe.pagebound.core.page.PageWriteService;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import io.github.yuchoe.pagebound.core.tag.TagStore;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Args;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Caller;
import io.github.yuchoe.pagebound.server.mcp.McpTool.Schema;

/**
 * MCP 쓰기 도구 6개 (spec/features/F6-mcp-api.md 3.2절). scope write + EDITOR 이상만 노출·실행.
 */
@Component
public class McpWriteTools {

    /** MCP 첨부 1건 상한 (base64 전송 고려, F6 1절) */
    static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final McpReadTools read;
    private final PageWriteService writes;
    private final PageTreeService tree;
    private final AttachmentService attachments;
    private final TagStore tags;

    public McpWriteTools(McpReadTools read, PageWriteService writes, PageTreeService tree,
                         AttachmentService attachments, TagStore tags) {
        this.read = read;
        this.writes = writes;
        this.tree = tree;
        this.attachments = attachments;
        this.tags = tags;
    }

    public List<McpTool> tools() {
        Map<String, Object> edits = Map.of("type", "array", "description",
                "정확 일치 치환 목록. old는 현재 본문에서 정확히 1회 나와야 한다 (아니면 EDIT_NOT_UNIQUE, 전체 거부).",
                "items", Map.of("type", "object", "required", List.of("old", "new"), "properties", Map.of(
                        "old", Map.of("type", "string"), "new", Map.of("type", "string"))));
        return List.of(
                new McpTool("create_page", """
                        문서 생성. 경로 = parent_path/slug (slug 생략 시 title). 상위 문서는 이미 있어야 한다. \
                        본문 문법은 서버 instructions 참조 (위키 링크 [[경로]], 첨부 /p/{id}/files/{이름}).""",
                        Schema.object().str("parent_path", "상위 문서 경로 (없으면 최상위)", false)
                                .str("title", "제목", true).str("slug", "경로 마지막 이름 (/#?|[] 불가)", false)
                                .str("content", "마크다운 본문", true).strArray("tags", "태그")
                                .str("summary", "변경 요약", false).build(), true, this::createPage),
                new McpTool("update_page", """
                        문서 수정. base_version 필수(get_page의 version). 다음 중 하나: content(전체 교체) / \
                        section + content(섹션 교체, 헤딩 줄 포함) / edits[{old,new}]. dry_run=true면 저장하지 않고 diff만.""",
                        Schema.object().str("path", "문서 경로", true).integer("base_version", "기준 버전", true)
                                .str("content", "새 본문 (전체 또는 섹션)", false)
                                .str("section", "섹션 번호(2.1) 또는 헤딩 텍스트", false)
                                .prop("edits", edits, false).str("summary", "변경 요약", false)
                                .bool("dry_run", "true면 diff만 반환").build(), true, this::updatePage),
                new McpTool("set_tags", "태그 추가·제거.",
                        Schema.object().str("path", "문서 경로", true).strArray("add", "추가할 태그")
                                .strArray("remove", "제거할 태그").build(), true, this::setTags),
                new McpTool("move_page", """
                        문서 이동·이름 변경 (하위 문서 포함). 다른 문서의 링크는 자동 갱신, 이전 경로는 리다이렉트로 남는다.""",
                        Schema.object().str("path", "문서 경로", true)
                                .str("new_parent_path", "새 상위 경로 (\"\"=최상위, 생략=유지)", false)
                                .str("new_slug", "새 이름 (생략=유지)", false).build(), true, this::movePage),
                new McpTool("upload_attachment", """
                        문서에 첨부 업로드 (최대 10MB). 반환 markdown을 update_page로 본문에 넣는다. \
                        같은 이름이 있으면 서버가 접미사를 붙인다.""",
                        Schema.object().str("path", "문서 경로", true).str("filename", "파일명", true)
                                .str("content_base64", "파일 내용 (base64)", true).build(), true, this::uploadAttachment),
                new McpTool("trash_page", "문서를 휴지통으로 이동 (하위 문서가 있으면 거부). 복원은 웹 관리자만.",
                        Schema.object().str("path", "문서 경로", true).build(), true, this::trashPage));
    }

    private Object createPage(Args a, Caller c) {
        String parent = a.optStr("parent_path") == null ? "" : WikiPaths.normalize(a.optStr("parent_path"));
        String title = a.str("title").strip();
        String slug = a.optStr("slug") == null || a.optStr("slug").isBlank() ? title : a.optStr("slug").strip();
        var saved = writes.create(WikiPaths.join(parent, slug), title, a.str("content"), a.strList("tags"),
                a.optStr("summary"), false, c.actor());
        return saved(saved, c);
    }

    private Object updatePage(Args a, Caller c) {
        String path = McpReadTools.path(a);
        long id = read.requireId(path);
        int baseVersion = a.requiredInt("base_version");
        var current = writes.source(id, null);
        if (current.version() != baseVersion) {
            throw new WikiException(409, "VERSION_CONFLICT", "page was modified by someone else",
                    Map.of("currentVersion", current.version()));
        }
        List<Map<String, Object>> edits = a.objectList("edits");
        int modes = (edits != null ? 1 : 0) + (a.has("content") ? 1 : 0);
        if (modes != 1) {
            throw WikiException.badRequest("provide exactly one of content (optionally with section) or edits");
        }
        Integer section = null;
        String oldText = current.content();
        String newText;
        if (edits != null) {
            if (a.has("section")) {
                throw WikiException.badRequest("section cannot be combined with edits");
            }
            newText = applyEdits(oldText, edits);
        } else if (a.has("section")) {
            var s = read.findSection(oldText, a.str("section"));
            section = s.index();
            oldText = oldText.substring(s.start(), s.end());
            newText = a.optStr("content");
        } else {
            newText = a.optStr("content");
        }
        if (a.bool("dry_run")) {
            return Map.of("dryRun", true, "baseVersion", baseVersion, "diff", diff(path, oldText, newText));
        }
        var saved = writes.update(id, baseVersion, null, newText, null, a.optStr("summary"), section, null, false,
                c.actor());
        return saved(saved, c);
    }

    /** 순서대로 적용. 각 old는 그 시점 본문에서 정확히 1회 일치해야 한다. */
    static String applyEdits(String content, List<Map<String, Object>> edits) {
        String text = content;
        for (int i = 0; i < edits.size(); i++) {
            Object oldValue = edits.get(i).get("old");
            Object newValue = edits.get(i).get("new");
            if (!(oldValue instanceof String old) || old.isEmpty() || !(newValue instanceof String replacement)) {
                throw WikiException.badRequest("edits[" + i + "] needs non-empty string old and string new");
            }
            int first = text.indexOf(old);
            int matches = first < 0 ? 0 : (text.indexOf(old, first + 1) < 0 ? 1 : 2);
            if (matches != 1) {
                throw new WikiException(409, "EDIT_NOT_UNIQUE",
                        "edits[" + i + "].old matched " + (matches == 0 ? "0 times" : "2 or more times"),
                        Map.of("index", i, "matches", matches));
            }
            text = text.substring(0, first) + replacement + text.substring(first + old.length());
        }
        return text;
    }

    private static String diff(String path, String oldText, String newText) {
        List<String> before = oldText.lines().toList();
        List<String> after = newText.lines().toList();
        var patch = DiffUtils.diff(before, after);
        return String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(path, path, before, patch, 3));
    }

    private Object setTags(Args a, Caller c) {
        String path = McpReadTools.path(a);
        long id = read.requireId(path);
        List<String> next = new ArrayList<>(writes.source(id, null).tags());
        List<String> remove = a.strList("remove");
        if (remove != null) {
            next.removeIf(t -> remove.stream().anyMatch(r -> r.strip().equalsIgnoreCase(t)));
        }
        List<String> add = a.strList("add");
        if (add != null) {
            next.addAll(add);
        }
        tags.replace(id, next);
        return Map.of("path", path, "tags", writes.source(id, null).tags());
    }

    private Object movePage(Args a, Caller c) {
        long id = read.requireId(McpReadTools.path(a));
        String newParent = a.optStr("new_parent_path") == null ? null : WikiPaths.normalize(a.optStr("new_parent_path"));
        var moved = tree.move(id, newParent, a.optStr("new_slug"), null, null, c.actor());
        return Map.of("path", moved.path(), "permalink", c.permalink(moved.id()), "version", moved.version(),
                "linkUpdatedPages", moved.linkUpdatedPages());
    }

    private Object uploadAttachment(Args a, Caller c) {
        long id = read.requireId(McpReadTools.path(a));
        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(a.str("content_base64"));
        } catch (IllegalArgumentException e) {
            throw WikiException.badRequest("content_base64 is not valid base64");
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new WikiException(413, "TOO_LARGE", "file exceeds " + MAX_UPLOAD_BYTES + " bytes",
                    Map.of("maxBytes", MAX_UPLOAD_BYTES));
        }
        var info = attachments.upload(id, a.str("filename"), null, data, c.userId());
        boolean image = info.mime() != null && info.mime().startsWith("image/");
        String markdown = (image ? "![" : "[") + info.filename() + "](" + info.url() + ")";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", info.filename());
        out.put("mime", info.mime());
        out.put("size", info.size());
        out.put("url", info.url());
        out.put("markdown", markdown);
        return out;
    }

    private Object trashPage(Args a, Caller c) {
        String path = McpReadTools.path(a);
        int count = tree.trash(read.requireId(path), false, c.userId());
        return Map.of("path", path, "trashedPages", count);
    }

    private static Map<String, Object> saved(PageWriteService.Saved saved, Caller c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", saved.path());
        out.put("permalink", c.permalink(saved.id()));
        out.put("version", saved.version());
        out.put("warnings", saved.warnings());
        return out;
    }
}
