package io.github.yuchoe.pagebound.server.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.yuchoe.pagebound.core.audit.AuditLog;
import io.github.yuchoe.pagebound.core.page.PageTreeService;
import io.github.yuchoe.pagebound.core.page.PageWriteService;
import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;
import io.github.yuchoe.pagebound.server.mcp.McpCallLog;

/**
 * 정리 작업: 24시간 지난 붙여넣기용 초안 (spec/features/F1-editor.md 2절 규칙 5),
 * 보관 기간이 지난 휴지통 문서 (F3 6절, 기본 30일), 감사 로그 1년·MCP 호출 로그 90일 (F12 9·12절).
 */
@Configuration
@EnableScheduling
public class DraftCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DraftCleanupJob.class);

    private final PageWriteService writes;
    private final PageTreeService tree;
    private final RuntimeSettings settings;
    private final AuditLog audit;
    private final McpCallLog callLog;

    public DraftCleanupJob(PageWriteService writes, PageTreeService tree, RuntimeSettings settings, AuditLog audit,
                           McpCallLog callLog) {
        this.writes = writes;
        this.tree = tree;
        this.settings = settings;
        this.audit = audit;
        this.callLog = callLog;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT24H")
    public void purgeTrash() {
        int purged = tree.purgeExpired(settings.trashRetentionDays());
        if (purged > 0) {
            log.info("expired trash purged: {}", purged);
        }
        audit.purgeOlderThan(365);
        callLog.purgeExpired();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    public void cleanup() {
        int deleted = writes.deleteStaleDrafts(Duration.ofHours(settings.draftRetentionHours()));
        if (deleted > 0) {
            log.info("stale drafts deleted: {}", deleted);
        }
    }
}
