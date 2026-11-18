package io.github.yuchoe.pagebound.core.attachment;

import java.net.URLConnection;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import io.github.yuchoe.pagebound.core.Actor;
import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.page.PageWriteService;

/**
 * 페이지 종속 첨부 (spec/features/F2-attachments.md). 첨부는 반드시 한 문서 소유.
 */
@Service
public class AttachmentService {

    public record Info(long id, String filename, String mime, long size, String url, boolean referenced) {
    }

    /** 이름 변경 결과. pageVersion은 본문 치환 시 증가한 문서 버전(치환 안 하면 기존 버전). */
    public record Renamed(Info attachment, int pageVersion) {
    }

    private final JdbcClient jdbc;
    private final AttachmentStorage storage;
    private final PageWriteService pageWrites;
    private final long maxFileSize;

    public AttachmentService(JdbcClient jdbc, AttachmentStorage storage, PageWriteService pageWrites,
                             @Value("${pagebound.upload.max-file-size:50MB}") DataSize maxFileSize) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.pageWrites = pageWrites;
        this.maxFileSize = maxFileSize.toBytes();
    }

    /** 업로드. 같은 문서에 같은 이름이 있으면 name-1.ext, name-2.ext … */
    @Transactional
    public Info upload(long pageId, String originalName, String mime, byte[] data, long actorId) {
        requirePage(pageId);
        if (data.length > maxFileSize) {
            throw new WikiException(413, "TOO_LARGE", "file exceeds " + maxFileSize + " bytes",
                    Map.of("maxBytes", maxFileSize));
        }
        String name = uniqueName(pageId, sanitize(originalName));
        String type = mime == null || mime.isBlank() || mime.equals("application/octet-stream")
                ? guessMime(name) : mime;
        jdbc.sql("""
                INSERT INTO attachments (page_id, filename, mime, size, sha256, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(pageId, name, type, data.length, AttachmentStorage.sha256(data), actorId, Instant.now().toString())
                .update();
        long id = jdbc.sql("SELECT last_insert_rowid()").query(Long.class).single();
        storage.write(pageId, id, data);
        return new Info(id, name, type, data.length, AttachmentStorage.url(pageId, name), isReferenced(pageId, name));
    }

    public List<Info> list(long pageId) {
        String content = requirePage(pageId);
        return jdbc.sql("SELECT id, filename, mime, size FROM attachments WHERE page_id = ? ORDER BY filename")
                .param(pageId)
                .query((rs, n) -> {
                    String url = AttachmentStorage.url(pageId, rs.getString(2));
                    return new Info(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), url,
                            content.contains(url));
                })
                .list();
    }

    /** 이름 변경. rewriteContent=true면 본문의 URL도 치환하고 리비전을 남긴다. */
    @Transactional
    public Renamed rename(long pageId, long attachmentId, String newName, boolean rewriteContent,
                          Actor actor) {
        String content = requirePage(pageId);
        Info current = find(pageId, attachmentId);
        String name = sanitize(newName);
        if (name.equals(current.filename())) {
            return new Renamed(current, currentVersion(pageId));
        }
        if (jdbc.sql("SELECT count(*) FROM attachments WHERE page_id = ? AND filename = ?").params(pageId, name)
                .query(Integer.class).single() > 0) {
            throw new WikiException(409, "NAME_EXISTS", "attachment already exists: " + name);
        }
        jdbc.sql("UPDATE attachments SET filename = ? WHERE id = ?").params(name, attachmentId).update();
        String newUrl = AttachmentStorage.url(pageId, name);
        int version = currentVersion(pageId);
        if (rewriteContent && content.contains(current.url())) {
            version = pageWrites.replaceContent(pageId, content.replace(current.url(), newUrl),
                    "첨부 이름 변경: " + current.filename() + " → " + name, actor);
        }
        return new Renamed(new Info(attachmentId, name, current.mime(), current.size(), newUrl,
                isReferenced(pageId, name)), version);
    }

    @Transactional
    public void delete(long pageId, long attachmentId) {
        requirePage(pageId);
        find(pageId, attachmentId);
        jdbc.sql("DELETE FROM attachments WHERE id = ?").param(attachmentId).update();
        storage.delete(pageId, attachmentId);
    }

    /** 다른 문서의 첨부를 이 문서로 복사 (본문이 다른 문서 첨부를 참조할 때). */
    @Transactional
    public Info copy(long targetPageId, long sourcePageId, String filename, long actorId) {
        record Src(long id, String mime) {
        }
        Src src = jdbc.sql("SELECT id, mime FROM attachments WHERE page_id = ? AND filename = ?")
                .params(sourcePageId, filename)
                .query((rs, n) -> new Src(rs.getLong(1), rs.getString(2)))
                .optional().orElseThrow(() -> WikiException.notFound("attachment " + filename));
        return upload(targetPageId, filename, src.mime(), storage.read(sourcePageId, src.id()), actorId);
    }

    /** 경로 구분자·제어 문자 제거, 앞뒤 공백 제거, 최대 200자. 비면 "file". */
    static String sanitize(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim();
        if (name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "file" : name;
    }

    private String uniqueName(long pageId, String name) {
        Set<String> used = new HashSet<>(jdbc.sql("SELECT filename FROM attachments WHERE page_id = ?").param(pageId)
                .query(String.class).list());
        int dot = name.lastIndexOf('.');
        String candidate = name;
        for (int n = 1; used.contains(candidate); n++) {
            candidate = dot > 0 ? name.substring(0, dot) + "-" + n + name.substring(dot) : name + "-" + n;
        }
        return candidate;
    }

    private static String guessMime(String name) {
        String guessed = URLConnection.guessContentTypeFromName(name);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    private Info find(long pageId, long attachmentId) {
        return jdbc.sql("SELECT id, filename, mime, size FROM attachments WHERE id = ? AND page_id = ?")
                .params(attachmentId, pageId)
                .query((rs, n) -> new Info(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        AttachmentStorage.url(pageId, rs.getString(2)), false))
                .optional().orElseThrow(() -> WikiException.notFound("attachment " + attachmentId));
    }

    private boolean isReferenced(long pageId, String name) {
        return requirePage(pageId).contains(AttachmentStorage.url(pageId, name));
    }

    private int currentVersion(long pageId) {
        return jdbc.sql("SELECT version FROM pages WHERE id = ?").param(pageId).query(Integer.class).single();
    }

    /** 문서(초안 포함, 휴지통 제외) 존재 확인 후 본문 반환. */
    private String requirePage(long pageId) {
        return jdbc.sql("SELECT content FROM pages WHERE id = ? AND deleted_at IS NULL").param(pageId)
                .query(String.class).optional().orElseThrow(() -> WikiException.notFound("page " + pageId));
    }
}
