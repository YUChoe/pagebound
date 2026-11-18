package io.github.yuchoe.pagebound.server.web;

import java.util.Arrays;
import java.util.List;

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

import io.github.yuchoe.pagebound.core.tag.TagStore;

/**
 * 태그 API (spec/features/F4-tags.md 3절). 조회는 읽기 권한, 이름 변경·병합·삭제는 ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    public record RenameRequest(String name) {
    }

    public record MergeRequest(long targetId) {
    }

    private final TagStore tags;
    private final CurrentUser currentUser;

    public TagController(TagStore tags, CurrentUser currentUser) {
        this.tags = tags;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<TagStore.TagCount> list(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "false") boolean includeUnused) {
        return tags.list(q, Math.clamp(limit, 1, 1000), includeUnused);
    }

    /** names=a,b → 두 태그를 모두 가진 문서. */
    @GetMapping("/pages")
    public List<TagStore.TaggedPage> pages(@RequestParam String names) {
        return tags.pagesWithAll(Arrays.asList(names.split(",")));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> rename(@PathVariable long id, @RequestBody RenameRequest r, Authentication auth) {
        tags.rename(id, r.name(), currentUser.id(auth));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/merge")
    public ResponseEntity<Void> merge(@PathVariable long id, @RequestBody MergeRequest r, Authentication auth) {
        tags.merge(id, r.targetId(), currentUser.id(auth));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        tags.delete(id, currentUser.id(auth));
        return ResponseEntity.noContent().build();
    }
}
