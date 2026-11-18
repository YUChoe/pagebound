package io.github.yuchoe.pagebound.server.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.page.PageWriteService;

/**
 * 문서 쓰기 API (spec/features/F1-editor.md 4절). EDITOR/ADMIN만 호출 가능 (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1")
public class PageWriteController {

    /** 선택 항목은 wrapper 타입: Jackson 3는 누락된 primitive를 거부한다(FAIL_ON_NULL_FOR_PRIMITIVES). */
    public record CreateRequest(String path, String title, String content, List<String> tags, String summary,
                                Boolean createParents) {
    }

    /** path는 초안을 정식 문서로 저장할 때만 사용. */
    public record UpdateRequest(int version, String title, String content, List<String> tags, String summary,
                                Integer section, String path, Boolean createParents) {
    }

    private final PageWriteService writes;
    private final CurrentUser currentUser;

    public PageWriteController(PageWriteService writes, CurrentUser currentUser) {
        this.writes = writes;
        this.currentUser = currentUser;
    }

    @PostMapping("/pages")
    public ResponseEntity<PageWriteService.Saved> create(@RequestBody CreateRequest r, Authentication auth) {
        var saved = writes.create(r.path(), r.title(), r.content(), r.tags(), r.summary(), Boolean.TRUE.equals(r.createParents()),
                currentUser.actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping("/pages/drafts")
    public ResponseEntity<Map<String, Long>> draft(Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", writes.createDraft(currentUser.id(auth))));
    }

    @GetMapping("/pages/{id}/source")
    public PageWriteService.Source source(@PathVariable long id, @RequestParam(required = false) Integer section) {
        return writes.source(id, section);
    }

    @PutMapping("/pages/{id}")
    public PageWriteService.Saved update(@PathVariable long id, @RequestBody UpdateRequest r, Authentication auth) {
        return writes.update(id, r.version(), r.title(), r.content(), r.tags(), r.summary(), r.section(), r.path(),
                Boolean.TRUE.equals(r.createParents()), currentUser.actor(auth));
    }
}
