package io.github.yuchoe.pagebound.migrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * CLI 옵션: --source database.sqlite --target data/ [--force]
 */
record MigrationOptions(Path source, Path target, boolean force) {

    static MigrationOptions parse(String[] args) {
        Path source = null;
        Path target = null;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--force")) {
                force = true;
            } else if (a.startsWith("--source=")) {
                source = Path.of(a.substring(9));
            } else if (a.startsWith("--target=")) {
                target = Path.of(a.substring(9));
            } else if (a.equals("--source") && i + 1 < args.length) {
                source = Path.of(args[++i]);
            } else if (a.equals("--target") && i + 1 < args.length) {
                target = Path.of(args[++i]);
            } else {
                throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        if (source == null || target == null) {
            throw new IllegalArgumentException("usage: --source <wikijs database.sqlite> --target <data dir> [--force]");
        }
        return new MigrationOptions(source.toAbsolutePath().normalize(), target.toAbsolutePath().normalize(), force);
    }

    Path dbFile() {
        return target.resolve("pagebound.db");
    }

    Path reportFile() {
        return target.resolve("migration-report.md");
    }

    /** 원본 존재 확인, 대상 정리(--force) 또는 기존 대상 존재 시 중단. */
    void prepareTarget() {
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("source not found: " + source);
        }
        Path attachments = target.resolve("attachments");
        boolean exists = Files.exists(dbFile()) || Files.exists(attachments);
        if (exists && !force) {
            throw new IllegalStateException("target already exists (use --force to recreate): " + target);
        }
        try {
            Files.createDirectories(target);
            for (String suffix : new String[] {"", "-wal", "-shm"}) {
                Files.deleteIfExists(target.resolve("pagebound.db" + suffix));
            }
            Files.deleteIfExists(reportFile());
            if (Files.exists(attachments)) {
                try (Stream<Path> walk = Files.walk(attachments)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
