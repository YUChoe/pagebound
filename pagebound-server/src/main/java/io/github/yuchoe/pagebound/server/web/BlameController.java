package io.github.yuchoe.pagebound.server.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.page.BlameService;

/**
 * 행별 blame API (spec/features/F13-blame.md 6절). 권한은 문서 조회와 동일.
 */
@RestController
public class BlameController {

    private final BlameService blame;

    public BlameController(BlameService blame) {
        this.blame = blame;
    }

    @GetMapping("/api/v1/pages/{id}/blame")
    public BlameService.Blame blame(@PathVariable long id) {
        return blame.blame(id);
    }
}
