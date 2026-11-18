package io.github.yuchoe.pagebound.server.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.yuchoe.pagebound.core.page.PageWriteService;

/**
 * 붙여넣기용 초안 중 24시간 지난 것을 첨부와 함께 정리 (spec/features/F1-editor.md 2절 규칙 5).
 */
@Configuration
@EnableScheduling
public class DraftCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DraftCleanupJob.class);

    private final PageWriteService writes;

    public DraftCleanupJob(PageWriteService writes) {
        this.writes = writes;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    public void cleanup() {
        int deleted = writes.deleteStaleDrafts(Duration.ofHours(24));
        if (deleted > 0) {
            log.info("stale drafts deleted: {}", deleted);
        }
    }
}
