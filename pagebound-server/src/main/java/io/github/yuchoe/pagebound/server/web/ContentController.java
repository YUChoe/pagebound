package io.github.yuchoe.pagebound.server.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.util.UriUtils;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.PageReadService;
import io.github.yuchoe.pagebound.core.page.WikiPaths;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 문서 원문(/w/{path}.md), 영구 링크(/p/{id}), 첨부(/p/{id}/files/{filename}).
 */
@Controller
public class ContentController {

    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final PageReadService pages;
    private final AttachmentStorage storage;

    public ContentController(PageReadService pages, AttachmentStorage storage) {
        this.pages = pages;
        this.storage = storage;
    }

    /** /w/{path}.md 는 원문, 그 외 /w/** 는 SPA 셸. */
    @GetMapping("/w/{*path}")
    public void page(@PathVariable String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!path.endsWith(".md")) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        String normalized = WikiPaths.normalize(path.substring(0, path.length() - 3));
        var content = pages.content(normalized);
        if (content.isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        response.setContentType(MARKDOWN.toString());
        response.getWriter().write(content.get());
    }

    @GetMapping("/p/{id:\\d+}")
    public void permalink(@PathVariable long id, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        // 휴지통·삭제 문서는 404 상태로 SPA의 "문서를 찾을 수 없습니다" 화면 (F5 2.1절 규칙 3)
        var path = pages.pathById(id);
        if (path.isPresent()) {
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, "/w/" + UriUtils.encodePath(path.get(), StandardCharsets.UTF_8));
            return;
        }
        response.setStatus(HttpStatus.NOT_FOUND.value());
        request.getRequestDispatcher("/index.html").forward(request, response);
    }

    /** 이미지·PDF inline, 그 외 다운로드. SVG는 CSP sandbox. ETag(sha256) + Range 지원. */
    @GetMapping("/p/{id:\\d+}/files/{filename}")
    public ResponseEntity<Resource> attachment(@PathVariable long id, @PathVariable String filename,
                                               WebRequest webRequest) {
        var found = pages.attachment(id, filename);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var file = found.get();
        String etag = "\"" + file.sha256() + "\"";
        if (webRequest.checkNotModified(etag)) {
            return null;
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.mime());
        } catch (InvalidMediaTypeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        boolean inline = "image".equals(type.getType()) || MediaType.APPLICATION_PDF.equalsTypeAndSubtype(type);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.filename(), StandardCharsets.UTF_8).build();
        var builder = ResponseEntity.ok()
                .eTag(etag)
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if ("svg+xml".equals(type.getSubtype())) {
            builder.header("Content-Security-Policy", "sandbox");
        }
        return builder.body(new FileSystemResource(storage.pathOf(file.pageId(), file.attachmentId())));
    }
}
