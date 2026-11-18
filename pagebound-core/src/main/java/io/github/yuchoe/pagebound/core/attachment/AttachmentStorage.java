package io.github.yuchoe.pagebound.core.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 첨부 파일 실체 저장: {data-dir}/attachments/{page_id}/{attachment_id}
 */
@Component
public class AttachmentStorage {

    private final Path root;

    public AttachmentStorage(@Value("${pagebound.data-dir}") Path dataDir) {
        this.root = dataDir.resolve("attachments");
    }

    public Path pathOf(long pageId, long attachmentId) {
        return root.resolve(Long.toString(pageId)).resolve(Long.toString(attachmentId));
    }

    public void write(long pageId, long attachmentId, byte[] data) {
        Path file = pathOf(pageId, attachmentId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] read(long pageId, long attachmentId) {
        try {
            return Files.readAllBytes(pathOf(pageId, attachmentId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(long pageId, long attachmentId) {
        try {
            Files.deleteIfExists(pathOf(pageId, attachmentId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 문서의 첨부 디렉토리 전체 삭제 (문서 영구 삭제·초안 정리). */
    public void deletePage(long pageId) {
        Path dir = root.resolve(Long.toString(pageId));
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 본문 저장 형식 URL: /p/{pageId}/files/{filename} (filename UTF-8 퍼센트 인코딩, 공백 %20). */
    public static String url(long pageId, String filename) {
        return "/p/" + pageId + "/files/" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
