package io.github.yuchoe.pagebound.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 백업 (spec/phases/M8-cutover.md 2번): pagebound.db 온라인 백업(VACUUM INTO) + data/attachments 복사, 일 1회, N일 보관.
 * 결과: {backup-dir}/{yyyyMMdd-HHmmss}/pagebound.db, attachments/. 복구는 deploy/README.md 절차.
 */
@Service
public class BackupService {

    public record Result(String name, long dbBytes, int files, long ms) {
    }

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final JdbcClient jdbc;
    private final Path dataDir;
    private final Path backupDir;
    private final int retentionDays;
    private final boolean enabled;

    public BackupService(JdbcClient jdbc, @Value("${pagebound.data-dir}") Path dataDir,
                         @Value("${pagebound.backup.dir:}") String backupDir,
                         @Value("${pagebound.backup.retention-days:7}") int retentionDays,
                         @Value("${pagebound.backup.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.dataDir = dataDir;
        this.backupDir = backupDir.isBlank() ? dataDir.resolve("backups") : Path.of(backupDir);
        this.retentionDays = retentionDays;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${pagebound.backup.cron:0 30 3 * * *}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            Result r = run();
            log.info("backup {} db={}B files={} {}ms", r.name(), r.dbBytes(), r.files(), r.ms());
        } catch (RuntimeException e) {
            log.error("backup failed", e);
        }
    }

    public synchronized Result run() {
        long started = System.nanoTime();
        String name = LocalDateTime.now().format(NAME);
        Path target = backupDir.resolve(name);
        try {
            Files.createDirectories(target);
            Path db = target.resolve("pagebound.db");
            jdbc.sql("VACUUM INTO '" + db.toAbsolutePath().toString().replace("'", "''") + "'").update();
            int files = copyTree(dataDir.resolve("attachments"), target.resolve("attachments"));
            Files.writeString(target.resolve("backup.done"), name + "\n");
            prune();
            return new Result(name, Files.size(db), files, (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 완료 표식이 있는 가장 최근 백업 이름 (대시보드용). */
    public Optional<String> latest() {
        return completed().stream().max(Comparator.naturalOrder());
    }

    private List<String> completed() {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(backupDir)) {
            return dirs.filter(d -> Files.exists(d.resolve("backup.done"))).map(d -> d.getFileName().toString()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 보관 기간이 지난 백업 삭제 (가장 최근 1개는 항상 유지). */
    private void prune() throws IOException {
        String cutoff = LocalDateTime.now().minusDays(retentionDays).format(NAME);
        List<String> names = completed().stream().sorted().toList();
        for (String name : names.subList(0, Math.max(0, names.size() - 1))) {
            if (name.compareTo(cutoff) < 0) {
                deleteTree(backupDir.resolve(name));
            }
        }
    }

    private static int copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.createDirectories(to);
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                    count[0]++;
                }
            }
        }
        return count[0];
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
