package io.github.yuchoe.pagebound.server.web;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.page.PageTreeService;

/**
 * 트리 편집 API (spec/features/F3-tree-links.md 7절). EDITOR/ADMIN.
 */
@RestController
@RequestMapping("/api/v1/pages/{id}")
public class TreeController {

    /** parentPath: null=부모 유지, ""=최상위. slug·title: null=유지. position: 형제 사이 위치. */
    public record MoveRequest(String parentPath, String slug, String title, Integer position) {
    }

    private final PageTreeService tree;
    private final CurrentUser currentUser;

    public TreeController(PageTreeService tree, CurrentUser currentUser) {
        this.tree = tree;
        this.currentUser = currentUser;
    }

    @PostMapping("/move")
    public PageTreeService.Moved move(@PathVariable long id, @RequestBody MoveRequest r, Authentication auth) {
        return tree.move(id, r.parentPath(), r.slug(), r.title(), r.position(), currentUser.id(auth), "web");
    }

    /** 휴지통으로 이동. 하위 문서가 있으면 includeChildren=true 필요 (409 HAS_CHILDREN). */
    @DeleteMapping
    public Map<String, Integer> trash(@PathVariable long id, @RequestParam(defaultValue = "false") boolean includeChildren,
                                      Authentication auth) {
        return Map.of("pages", tree.trash(id, includeChildren, currentUser.id(auth)));
    }
}
