package io.github.yuchoe.pagebound.server.web;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.yuchoe.pagebound.core.attachment.AttachmentService;

/**
 * 첨부 API (spec/features/F2-attachments.md 5절).
 */
@RestController
@RequestMapping("/api/v1/pages/{pageId}/attachments")
public class AttachmentController {

    public record RenameRequest(String filename, Boolean rewriteContent) {
    }

    public record CopyRequest(long sourcePageId, String filename) {
    }

    private final AttachmentService attachments;
    private final CurrentUser currentUser;

    public AttachmentController(AttachmentService attachments, CurrentUser currentUser) {
        this.attachments = attachments;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AttachmentService.Info> list(@PathVariable long pageId) {
        return attachments.list(pageId);
    }

    /** multipart "file". 같은 이름이 있으면 서버가 접미사를 붙여 최종 이름을 돌려준다. */
    @PostMapping
    public ResponseEntity<AttachmentService.Info> upload(@PathVariable long pageId,
                                                         @RequestParam("file") MultipartFile file,
                                                         Authentication auth) throws IOException {
        var info = attachments.upload(pageId, file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                currentUser.id(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }

    @PatchMapping("/{attachmentId}")
    public AttachmentService.Renamed rename(@PathVariable long pageId, @PathVariable long attachmentId,
                                            @RequestBody RenameRequest r, Authentication auth) {
        return attachments.rename(pageId, attachmentId, r.filename(), Boolean.TRUE.equals(r.rewriteContent()),
                currentUser.id(auth), "web");
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable long pageId, @PathVariable long attachmentId) {
        attachments.delete(pageId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/copy")
    public ResponseEntity<AttachmentService.Info> copy(@PathVariable long pageId, @RequestBody CopyRequest r,
                                                       Authentication auth) {
        var info = attachments.copy(pageId, r.sourcePageId(), r.filename(), currentUser.id(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(info);
    }
}
