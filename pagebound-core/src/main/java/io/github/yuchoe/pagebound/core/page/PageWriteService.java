package io.github.yuchoe.pagebound.core.page;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.yuchoe.pagebound.core.Actor;
import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.markdown.MarkdownAnalyzer;
import io.github.yuchoe.pagebound.core.tag.TagStore;

/**
 * 문서 생성·수정·섹션 편집·초안 (spec/features/F1-editor.md, F3-tree-links.md).
 * 웹 UI, REST, MCP가 공용으로 쓴다. source = web | mcp | api | system.
 */
@Service
public class PageWriteService {

    private static final Pattern FILE_URL = Pattern.compile("/p/(\\d+)/files/([^)\\s\"'>]+)");

    public record Saved(long id, String path, int version, List<String> warnings) {
    }

    public record Source(long id, String path, String title, String content, int version, List<String> tags,
                         boolean draft, Integer section) {
    }

    private record Row(long id, String path, String title, String content, int version, boolean draft) {
    }

    private final JdbcClient jdbc;
    private final PageStore pages;
    private final LinkIndexer links;
    private final TagStore tags;
    private final AttachmentStorage storage;
    private final MarkdownAnalyzer analyzer = new MarkdownAnalyzer();

    public PageWriteService(JdbcClient jdbc, PageStore pages, LinkIndexer links, TagStore tags,
                            AttachmentStorage storage) {
        this.jdbc = jdbc;
        this.pages = pages;
        this.links = links;
        this.tags = tags;
        this.storage = storage;
    }

    /** 경로로 새 문서 생성. 부모가 없으면 createParents=true일 때 빈 문서로 함께 만든다. */
    @Transactional
    public Saved create(String path, String title, String content, List<String> tagNames, String summary,
                        boolean createParents, Actor actor) {
        String normalized = validatePath(path);
        if (pages.findActiveIdByPath(normalized).isPresent()) {
            throw new WikiException(409, "PATH_EXISTS", "page already exists: " + normalized);
        }
        Long parentId = resolveParent(WikiPaths.parentOf(normalized), createParents, actor);
        String now = Instant.now().toString();
        String finalTitle = title == null || title.isBlank() ? WikiPaths.lastSegment(normalized) : title.trim();
        long id = pages.insert(new PageStore.NewPage(null, parentId, WikiPaths.lastSegment(normalized), finalTitle,
                content == null ? "" : content, nextSortOrder(parentId), 1, actor.userId(), actor.userId(), now, now));
        pages.insertRevision(new PageStore.NewRevision(id, 1, finalTitle, normalized, content == null ? "" : content,
                "created", actor.source(), summary, actor.userId(), actor.tokenId(), now));
        if (tagNames != null) {
            tags.replace(id, tagNames);
        }
        afterContentChange(id, normalized);
        return new Saved(id, normalized, 1, warnings(id));
    }

    /** 붙여넣기용 초안: 트리·검색에 보이지 않는 임시 문서. 저장(update + publishPath) 시 정식 문서가 된다. */
    @Transactional
    public long createDraft(long actorId) {
        String slug = "draft-" + UUID.randomUUID();
        String now = Instant.now().toString();
        long id = pages.insert(new PageStore.NewPage(null, null, slug, slug, "", 0, 1, actorId, actorId, now, now));
        jdbc.sql("UPDATE pages SET is_draft = 1 WHERE id = ?").param(id).update();
        return id;
    }

    /**
     * 수정. baseVersion 불일치 시 409 VERSION_CONFLICT. section이 있으면 해당 섹션만 교체.
     * 초안이면 publishPath로 정식 문서가 된다.
     */
    @Transactional
    public Saved update(long id, int baseVersion, String title, String content, List<String> tagNames, String summary,
                        Integer section, String publishPath, boolean createParents, Actor actor) {
        Row row = load(id);
        if (row.version() != baseVersion) {
            throw new WikiException(409, "VERSION_CONFLICT", "page was modified by someone else", Map.of(
                    "currentVersion", row.version(), "currentTitle", row.title(), "currentContent", row.content()));
        }
        String now = Instant.now().toString();
        String finalTitle = title == null || title.isBlank() ? row.title() : title.trim();
        String path = row.path();
        int version;
        if (row.draft()) {
            path = validatePath(publishPath);
            if (pages.findActiveIdByPath(path).isPresent()) {
                throw new WikiException(409, "PATH_EXISTS", "page already exists: " + path);
            }
            Long parentId = resolveParent(WikiPaths.parentOf(path), createParents, actor);
            if (title == null || title.isBlank()) {
                finalTitle = WikiPaths.lastSegment(path);
            }
            jdbc.sql("""
                    UPDATE pages SET parent_id = ?, slug = ?, path = ?, title = ?, content = ?, is_draft = 0,
                           sort_order = ?, updated_by = ?, updated_at = ?, created_at = ? WHERE id = ?""")
                    .params(parentId, WikiPaths.lastSegment(path), path, finalTitle, nullToEmpty(content),
                            nextSortOrder(parentId), actor.userId(), now, now, id)
                    .update();
            version = 1;
            pages.insertRevision(new PageStore.NewRevision(id, 1, finalTitle, path, nullToEmpty(content), "created",
                    actor.source(), summary, actor.userId(), actor.tokenId(), now));
        } else {
            String newContent = section == null ? nullToEmpty(content) : mergeSection(row.content(), section, content);
            version = row.version() + 1;
            int updated = jdbc.sql("""
                    UPDATE pages SET title = ?, content = ?, version = ?, updated_by = ?, updated_at = ?
                    WHERE id = ? AND version = ?""")
                    .params(finalTitle, newContent, version, actor.userId(), now, id, baseVersion).update();
            if (updated != 1) {
                throw new WikiException(409, "VERSION_CONFLICT", "page was modified by someone else");
            }
            pages.insertRevision(new PageStore.NewRevision(id, version, finalTitle, path, newContent, "updated",
                    actor.source(), summary, actor.userId(), actor.tokenId(), now));
        }
        if (tagNames != null) {
            tags.replace(id, tagNames);
        }
        afterContentChange(id, path);
        return new Saved(id, path, version, warnings(id));
    }

    /** 버전 검사 없이 본문 교체 (첨부 이름 변경에 따른 URL 치환 등 시스템 수정). */
    @Transactional
    public int replaceContent(long id, String newContent, String summary, Actor actor) {
        Row row = load(id);
        if (row.draft()) {
            jdbc.sql("UPDATE pages SET content = ? WHERE id = ?").params(newContent, id).update();
            return row.version();
        }
        String now = Instant.now().toString();
        int version = row.version() + 1;
        jdbc.sql("UPDATE pages SET content = ?, version = ?, updated_by = ?, updated_at = ? WHERE id = ?")
                .params(newContent, version, actor.userId(), now, id).update();
        pages.insertRevision(new PageStore.NewRevision(id, version, row.title(), row.path(), newContent, "updated",
                actor.source(), summary, actor.userId(), actor.tokenId(), now));
        links.rebuild(id);
        return version;
    }

    /** 특정 버전의 제목·본문으로 되돌린다: 새 버전 action='restored' (spec/features/F7-common.md 2절). 경로는 유지. */
    @Transactional
    public Saved restore(long id, int version, Actor actor) {
        Row row = load(id);
        if (row.draft()) {
            throw WikiException.badRequest("draft cannot be restored");
        }
        record Rev(String title, String content) {
        }
        Rev rev = jdbc.sql("SELECT title, content FROM page_revisions WHERE page_id = ? AND version = ?")
                .params(id, version).query((rs, n) -> new Rev(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> WikiException.notFound("revision " + version));
        String now = Instant.now().toString();
        int next = row.version() + 1;
        jdbc.sql("UPDATE pages SET title = ?, content = ?, version = ?, updated_by = ?, updated_at = ? WHERE id = ?")
                .params(rev.title(), rev.content(), next, actor.userId(), now, id).update();
        pages.insertRevision(new PageStore.NewRevision(id, next, rev.title(), row.path(), rev.content(), "restored",
                actor.source(), "v" + version + " 복원", actor.userId(), actor.tokenId(), now));
        afterContentChange(id, row.path());
        return new Saved(id, row.path(), next, warnings(id));
    }

    /** 편집용 원문. section이 있으면 그 섹션 텍스트만. */
    public Source source(long id, Integer section) {
        Row row = load(id);
        String content = row.content();
        if (section != null) {
            MarkdownAnalyzer.Section s = findSection(content, section);
            content = content.substring(s.start(), s.end());
        }
        List<String> tagNames = jdbc.sql("""
                SELECT t.name FROM page_tags pt JOIN tags t ON t.id = pt.tag_id WHERE pt.page_id = ?
                ORDER BY t.name COLLATE NOCASE""").param(id).query(String.class).list();
        return new Source(id, row.path(), row.title(), content, row.version(), tagNames, row.draft(), section);
    }

    /** 24시간 등 age보다 오래된 초안과 그 첨부 파일 삭제. 삭제 건수 반환. */
    @Transactional
    public int deleteStaleDrafts(Duration age) {
        String cutoff = Instant.now().minus(age).toString();
        List<Long> ids = jdbc.sql("SELECT id FROM pages WHERE is_draft = 1 AND created_at < ?").param(cutoff)
                .query(Long.class).list();
        for (long id : ids) {
            jdbc.sql("DELETE FROM pages WHERE id = ?").param(id).update();
            storage.deletePage(id);
        }
        return ids.size();
    }

    /**
     * 코드블록 기본 접힘 변경 (spec/features/F9-code-blocks.md 4.3절): index번째 펜스(0부터, mermaid 포함)의
     * info string에서 collapsed 토큰만 추가·제거해 새 리비전으로 저장. version 불일치는 409.
     */
    @Transactional
    public Saved setCodeBlockCollapsed(long id, int version, int index, boolean collapsed, Actor actor) {
        String content = load(id).content();
        List<MarkdownAnalyzer.Range> infos = analyzer.fenceInfos(content);
        if (index < 0 || index >= infos.size()) {
            throw WikiException.notFound("code block " + index);
        }
        MarkdownAnalyzer.Range r = infos.get(index);
        String info = content.substring(r.start(), r.end());
        String without = info.replaceAll("(^|\\s+)collapsed(?=\\s|$)", "").strip();
        String next = collapsed ? (without.isEmpty() ? "collapsed" : without + " collapsed") : without;
        String newContent = content.substring(0, r.start()) + next + content.substring(r.end());
        return update(id, version, null, newContent, null, "코드블록 기본 접힘 변경", null, null, false, actor);
    }

    /** 초안 1건 수동 삭제 (첨부 파일 포함). 초안이 아니면 false. */
    @Transactional
    public boolean deleteDraft(long id) {
        int deleted = jdbc.sql("DELETE FROM pages WHERE id = ? AND is_draft = 1").param(id).update();
        if (deleted > 0) {
            storage.deletePage(id);
        }
        return deleted > 0;
    }

    /** 다른 문서 첨부 참조, 없는 첨부 참조 경고 (spec/features/F2-attachments.md 4절). */
    public List<String> warnings(long id) {
        String content = load(id).content();
        List<String> warnings = new ArrayList<>();
        Matcher m = FILE_URL.matcher(content);
        while (m.find()) {
            long target = Long.parseLong(m.group(1));
            String name = java.net.URLDecoder.decode(m.group(2).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            if (target != id) {
                warnings.add("FOREIGN_ATTACHMENT:" + target + "/" + name);
            } else if (jdbc.sql("SELECT count(*) FROM attachments WHERE page_id = ? AND filename = ?")
                    .params(id, name).query(Integer.class).single() == 0) {
                warnings.add("MISSING_ATTACHMENT:" + name);
            }
        }
        return warnings.stream().distinct().toList();
    }

    private String mergeSection(String current, int section, String sectionText) {
        MarkdownAnalyzer.Section s = findSection(current, section);
        String text = nullToEmpty(sectionText);
        if (s.end() < current.length() && !text.endsWith("\n")) {
            text += "\n";
        }
        return current.substring(0, s.start()) + text + current.substring(s.end());
    }

    private MarkdownAnalyzer.Section findSection(String content, int section) {
        return analyzer.sections(content).stream().filter(s -> s.index() == section).findFirst()
                .orElseThrow(() -> new WikiException(404, "SECTION_NOT_FOUND", "section " + section + " not found"));
    }

    private Row load(long id) {
        return jdbc.sql("SELECT id, path, title, content, version, is_draft FROM pages WHERE id = ? AND deleted_at IS NULL")
                .param(id)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6) == 1))
                .optional().orElseThrow(() -> WikiException.notFound("page " + id));
    }

    private static String validatePath(String path) {
        String normalized = WikiPaths.normalize(path);
        if (normalized.isEmpty()) {
            throw WikiException.badRequest("path is required");
        }
        for (String segment : normalized.split("/")) {
            if (!WikiPaths.isValidSlug(segment)) {
                throw WikiException.badRequest("invalid path segment: " + segment);
            }
        }
        return normalized;
    }

    private Long resolveParent(String parentPath, boolean createParents, Actor actor) {
        if (parentPath.isEmpty()) {
            return null;
        }
        var existing = pages.findActiveIdByPath(parentPath);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!createParents) {
            throw new WikiException(409, "PARENT_NOT_FOUND", "parent page not found: " + parentPath,
                    Map.of("parent", parentPath));
        }
        return create(parentPath, null, "", null, "하위 문서 생성 시 자동 생성", true, actor).id();
    }

    private int nextSortOrder(Long parentId) {
        String where = parentId == null ? "parent_id IS NULL" : "parent_id = " + parentId;
        return jdbc.sql("SELECT coalesce(max(sort_order), -1) + 1 FROM pages WHERE " + where)
                .query(Integer.class).single();
    }

    /** 이 문서의 링크 재계산 + 이 경로를 가리키던 레드 링크를 연결. */
    private void afterContentChange(long id, String path) {
        links.rebuild(id);
        jdbc.sql("UPDATE page_links SET target_page_id = ? WHERE target_path = ? AND target_page_id IS NULL")
                .params(id, path).update();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
