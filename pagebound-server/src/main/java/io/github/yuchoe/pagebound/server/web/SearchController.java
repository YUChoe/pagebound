package io.github.yuchoe.pagebound.server.web;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.search.SearchService;

/**
 * 검색 API (spec/features/F11-search.md 9절). 권한은 조회 API와 동일.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public SearchService.Result search(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(required = false) String tags,
                                       @RequestParam(required = false) String path,
                                       @RequestParam(defaultValue = "relevance") String sort,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        List<String> tagList = tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
        return search.search(q, tagList, path, sort, page, size);
    }

    @GetMapping("/suggest")
    public List<SearchService.Suggestion> suggest(@RequestParam(defaultValue = "") String q) {
        return search.suggest(q, 8);
    }
}
