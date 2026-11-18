package io.github.yuchoe.pagebound.server.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.admin.AdminQueries;
import io.github.yuchoe.pagebound.core.audit.AuditLog;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.core.page.PageWriteService;
import io.github.yuchoe.pagebound.core.search.SearchService;
import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;
import io.github.yuchoe.pagebound.server.config.BackupService;
import io.github.yuchoe.pagebound.server.mcp.McpCallLog;
import io.github.yuchoe.pagebound.server.preview.GitLabProvider;

/**
 * 관리자 P1 API (spec/features/F12-admin.md 7·9·10·11절): 설정, 유지보수, 정리 대상, MCP 호출 로그.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpsController {

    public record TaskResult(String task, int count, long ms) {
    }

    private final RuntimeSettings settings;
    private final AdminQueries queries;
    private final McpCallLog callLog;
    private final GitLabProvider gitlab;
    private final LinkIndexer links;
    private final PageWriteService writes;
    private final SearchService search;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final CurrentUser currentUser;
    private final BackupService backup;
    private final Path dataDir;

    public AdminOpsController(RuntimeSettings settings, AdminQueries queries, McpCallLog callLog, GitLabProvider gitlab,
                              LinkIndexer links, PageWriteService writes, SearchService search, JdbcClient jdbc,
                              AuditLog audit, CurrentUser currentUser, BackupService backup,
                              @Value("${pagebound.data-dir}") Path dataDir) {
        this.settings = settings;
        this.queries = queries;
        this.callLog = callLog;
        this.gitlab = gitlab;
        this.links = links;
        this.writes = writes;
        this.search = search;
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
        this.backup = backup;
        this.dataDir = dataDir;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("items", settings.items(), "gitlab", gitlab.status());
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, String> changes, Authentication auth) {
        settings.update(changes, currentUser.id(auth));
        return settings();
    }

    /** task: reindex-search, rebuild-links, clear-link-previews, cleanup-drafts, backup(처리 건수 = 복사한 첨부 수) */
    @PostMapping("/maintenance/{task}")
    public TaskResult run(@PathVariable String task, Authentication auth) {
        long started = System.nanoTime();
        int count = switch (task) {
            case "reindex-search" -> search.rebuildIndex();
            case "rebuild-links" -> {
                links.rebuildAll();
                yield jdbc.sql("SELECT count(*) FROM page_links").query(Integer.class).single();
            }
            case "clear-link-previews" -> jdbc.sql("DELETE FROM link_previews").update();
            case "cleanup-drafts" -> writes.deleteStaleDrafts(Duration.ofHours(settings.draftRetentionHours()));
            case "backup" -> backup.run().files();
            default -> throw WikiException.notFound("task " + task);
        };
        long ms = (System.nanoTime() - started) / 1_000_000;
        audit.record(currentUser.id(auth), "MAINTENANCE", "maintenance", task, Map.of("count", count, "ms", ms));
        return new TaskResult(task, count, ms);
    }

    @GetMapping("/maintenance/migration-report")
    public Map<String, Object> migrationReport() throws IOException {
        Path report = dataDir.resolve("migration-report.md");
        return Files.exists(report)
                ? Map.of("exists", true, "content", Files.readString(report, StandardCharsets.UTF_8))
                : Map.of("exists", false);
    }

    /** kind: unreferenced-attachments, red-links, empty-pages, drafts */
    @GetMapping("/cleanup/{kind}")
    public List<?> cleanup(@PathVariable String kind) {
        return switch (kind) {
            case "unreferenced-attachments" -> queries.unreferencedAttachments();
            case "red-links" -> queries.redLinks();
            case "empty-pages" -> queries.emptyPages();
            case "drafts" -> queries.drafts();
            default -> throw WikiException.notFound("cleanup kind " + kind);
        };
    }

    /** 초안 수동 삭제 (첨부 파일 포함). */
    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable long id, Authentication auth) {
        if (!writes.deleteDraft(id)) {
            throw WikiException.notFound("draft " + id);
        }
        audit.record(currentUser.id(auth), "DRAFT_DELETE", "page", id, Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mcp-calls")
    public McpCallLog.CallPage mcpCalls(@RequestParam(required = false) Long token,
                                        @RequestParam(required = false) String tool,
                                        @RequestParam(required = false) String result,
                                        @RequestParam(defaultValue = "1") int page) {
        return callLog.list(token, tool, result, page);
    }
}
