package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.page.LinkIndexer;
import io.github.yuchoe.pagebound.server.config.BackupService;

/**
 * 백업 (spec/phases/M8-cutover.md 2번): 백업본으로 복구 가능해야 한다.
 */
@SpringBootTest(properties = {"pagebound.data-dir=target/it-backup", "pagebound.backup.dir=target/it-backup-out"})
class BackupTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    LinkIndexer links;
    @Autowired
    AttachmentStorage storage;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    BackupService backup;

    @Test
    void backupIsARestorableCopy() throws Exception {
        TestData.seed(jdbc, links, storage, encoder);
        var result = backup.run();
        Path dir = Path.of("target/it-backup-out", result.name());
        assertThat(result.files()).isEqualTo(3);
        assertThat(Files.exists(dir.resolve("backup.done"))).isTrue();
        assertThat(backup.latest()).contains(result.name());

        // 복구: 백업 DB를 열면 스키마 검증 통과, 데이터 일치
        String url = "jdbc:sqlite:" + dir.resolve("pagebound.db").toAbsolutePath();
        var validate = Flyway.configure().dataSource(url, null, null).load().validateWithResult();
        assertThat(validate.validationSuccessful).isTrue();
        var ds = new SingleConnectionDataSource(url, true);
        try {
            var restored = JdbcClient.create(ds);
            assertThat(restored.sql("SELECT count(*) FROM pages").query(Integer.class).single()).isEqualTo(3);
            assertThat(restored.sql("SELECT content FROM pages WHERE id = 2").query(String.class).single())
                    .isEqualTo(TestData.STAT_CONTENT);
        } finally {
            ds.destroy();
        }
        assertThat(Files.readAllBytes(dir.resolve("attachments/2/3"))).isEqualTo("ZIP".getBytes());
    }
}
