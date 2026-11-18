package io.github.yuchoe.pagebound.server.web;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.page.HistoryService;
import io.github.yuchoe.pagebound.core.page.PageWriteService;

/**
 * 역사 API (spec/features/F7-common.md 2절). 조회는 읽기 권한, 복원은 EDITOR/ADMIN (SecurityConfig POST 규칙).
 */
@RestController
@RequestMapping("/api/v1/pages/{id}")
public class HistoryController {

    private final HistoryService history;
    private final PageWriteService writes;
    private final CurrentUser currentUser;

    public HistoryController(HistoryService history, PageWriteService writes, CurrentUser currentUser) {
        this.history = history;
        this.writes = writes;
        this.currentUser = currentUser;
    }

    @GetMapping("/revisions")
    public List<HistoryService.Revision> revisions(@PathVariable long id, @RequestParam(defaultValue = "500") int limit) {
        return history.revisions(id, Math.clamp(limit, 1, 1000));
    }

    @GetMapping("/revisions/{version}")
    public HistoryService.RevisionContent revision(@PathVariable long id, @PathVariable int version) {
        return history.revision(id, version);
    }

    @GetMapping("/diff")
    public HistoryService.Diff diff(@PathVariable long id, @RequestParam int from, @RequestParam int to) {
        return history.diff(id, from, to);
    }

    @PostMapping("/revisions/{version}/restore")
    public PageWriteService.Saved restore(@PathVariable long id, @PathVariable int version, Authentication auth) {
        return writes.restore(id, version, currentUser.actor(auth));
    }
}
