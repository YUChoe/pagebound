package io.github.yuchoe.pagebound.server.web;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.page.PageTreeService;

/**
 * 관리자 휴지통 API (spec/features/F12-admin.md 6·14절). ADMIN만 (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/admin/trash")
public class AdminTrashController {

    public record RestoreRequest(String slug) {
    }

    private final PageTreeService tree;
    private final CurrentUser currentUser;
    private final int retentionDays;

    public AdminTrashController(PageTreeService tree, CurrentUser currentUser,
                                @Value("${pagebound.trash.retention-days:30}") int retentionDays) {
        this.tree = tree;
        this.currentUser = currentUser;
        this.retentionDays = retentionDays;
    }

    @GetMapping
    public List<PageTreeService.TrashItem> list() {
        return tree.trashList(retentionDays);
    }

    @PostMapping("/{id}/restore")
    public Map<String, String> restore(@PathVariable long id, @RequestBody(required = false) RestoreRequest r,
                                       Authentication auth) {
        return Map.of("path", tree.restore(id, r == null ? null : r.slug(), currentUser.id(auth)));
    }

    @DeleteMapping("/{id}")
    public Map<String, Integer> purge(@PathVariable long id, Authentication auth) {
        return Map.of("pages", tree.purge(id, currentUser.id(auth)));
    }
}
