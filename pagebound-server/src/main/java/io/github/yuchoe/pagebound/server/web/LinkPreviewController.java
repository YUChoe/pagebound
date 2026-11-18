package io.github.yuchoe.pagebound.server.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.server.preview.LinkPreviewService;

/**
 * 링크 미리보기 API (spec/features/F10-link-preview.md 9절). 로그인 사용자만 (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/link-previews")
public class LinkPreviewController {

    public record BatchRequest(List<String> urls) {
    }

    public record RefreshRequest(String url) {
    }

    private final LinkPreviewService previews;

    public LinkPreviewController(LinkPreviewService previews) {
        this.previews = previews;
    }

    @PostMapping
    public List<LinkPreviewService.Preview> batch(@RequestBody BatchRequest r) {
        return previews.previews(r.urls());
    }

    @PostMapping("/refresh")
    public LinkPreviewService.Preview refresh(@RequestBody RefreshRequest r) {
        return previews.refresh(r.url());
    }
}
