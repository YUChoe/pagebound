package io.github.yuchoe.pagebound.server.web;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.yuchoe.pagebound.core.admin.AdminQueries;
import io.github.yuchoe.pagebound.core.settings.RuntimeSettings;
import io.github.yuchoe.pagebound.core.user.UserAdminService;
import io.github.yuchoe.pagebound.server.config.BackupService;

/**
 * 관리자 API (spec/features/F12-admin.md 14절): 대시보드, 사용자, 감사 로그. ADMIN만 (SecurityConfig).
 * 휴지통은 AdminTrashController, 토큰은 TokenController, 태그는 TagController.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public record CreateUserRequest(String email, String name, String role, String password) {
    }

    public record UpdateUserRequest(String role, Boolean active) {
    }

    private static final String VERSION = "0.1.0";

    private final AdminQueries queries;
    private final UserAdminService users;
    private final PasswordEncoder encoder;
    private final CurrentUser currentUser;
    private final RuntimeSettings settings;
    private final BackupService backup;
    private final Path dataDir;

    public AdminController(AdminQueries queries, UserAdminService users, PasswordEncoder encoder,
                           CurrentUser currentUser, RuntimeSettings settings, BackupService backup,
                           @Value("${pagebound.data-dir}") Path dataDir) {
        this.queries = queries;
        this.users = users;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.settings = settings;
        this.backup = backup;
        this.dataDir = dataDir;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> d = new HashMap<>(queries.dashboard(settings.trashRetentionDays()));
        d.put("dbBytes", size(dataDir.resolve("pagebound.db")) + size(dataDir.resolve("pagebound.db-wal")));
        d.put("freeBytes", dataDir.toFile().getUsableSpace());
        d.put("lastBackup", backup.latest().orElse(null));
        d.put("version", VERSION);
        d.put("startedAt", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString());
        return d;
    }

    @GetMapping("/users")
    public List<UserAdminService.UserRow> users() {
        return users.list();
    }

    @PostMapping("/users")
    public ResponseEntity<UserAdminService.Created> create(@RequestBody CreateUserRequest r, Authentication auth) {
        var created = users.create(r.email(), r.name(), r.role(), r.password(), encoder::encode, currentUser.id(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody UpdateUserRequest r, Authentication auth) {
        users.update(id, r.role(), r.active(), currentUser.id(auth));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> resetPassword(@PathVariable long id, Authentication auth) {
        return Map.of("temporaryPassword", users.resetPassword(id, encoder::encode, currentUser.id(auth)));
    }

    @GetMapping("/audit")
    public AdminQueries.AuditPage audit(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long actor,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(defaultValue = "1") int page) {
        return queries.audit(from, to, actor, action, page, 50);
    }

    private static long size(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
