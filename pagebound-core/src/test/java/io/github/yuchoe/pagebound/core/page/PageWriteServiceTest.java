package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.util.unit.DataSize;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.core.attachment.AttachmentService;
import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;
import io.github.yuchoe.pagebound.core.tag.TagStore;

class PageWriteServiceTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private PageWriteService writes;
    private AttachmentService attachments;
    private AttachmentStorage storage;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tempDir.resolve("t.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, null, null).load().migrate();
        dataSource = new SingleConnectionDataSource(url, true);
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("INSERT INTO users (id, email, name, password_hash, role, created_at, updated_at) "
                + "VALUES (1, 'a@b.c', 'a', 'x', 'EDITOR', 't', 't')").update();
        PageStore pages = new PageStore(jdbc);
        storage = new AttachmentStorage(tempDir);
        writes = new PageWriteService(jdbc, pages, new LinkIndexer(jdbc, pages), new TagStore(jdbc), storage);
        attachments = new AttachmentService(jdbc, storage, writes, DataSize.ofMegabytes(1));
    }

    @AfterEach
    void close() {
        dataSource.destroy();
    }

    private Integer targetOf(String path) {
        return jdbc.sql("SELECT target_page_id FROM page_links WHERE target_path = ?").param(path)
                .query((rs, n) -> (Integer) rs.getObject(1)).list().getFirst();  // single()은 null 값을 허용하지 않음
    }

    @Test
    void createResolvesExistingRedLinksAndRequiresParent() {
        var home = writes.create("home", "홈", "[[nems/통계]]", List.of(), null, false, 1, "web");
        assertThat(targetOf("nems/통계")).isNull();
        assertThatThrownBy(() -> writes.create("nems/통계", null, "", null, null, false, 1, "web"))
                .isInstanceOf(WikiException.class).extracting("code").isEqualTo("PARENT_NOT_FOUND");

        var stat = writes.create("nems/통계", null, "본문", List.of("Java", "java", "통계"), "첫 작성", true, 1, "web");
        assertThat(stat.path()).isEqualTo("nems/통계");
        assertThat(targetOf("nems/통계")).isEqualTo((int) stat.id());
        assertThat(writes.source(stat.id(), null).tags()).containsExactly("Java", "통계");
        assertThat(jdbc.sql("SELECT title FROM pages WHERE path = 'nems'").query(String.class).single()).isEqualTo("nems");
        assertThatThrownBy(() -> writes.create("home", null, "", null, null, false, 1, "web"))
                .extracting("code").isEqualTo("PATH_EXISTS");
        assertThat(home.version()).isEqualTo(1);
    }

    @Test
    void updateChecksVersionAndMergesSection() {
        String content = "# 개요\n개요 본문\n## 설정\n설정 본문\n# 로드맵\n로드맵 본문\n";
        var page = writes.create("doc", null, content, null, null, false, 1, "web");
        assertThat(writes.source(page.id(), 2).content()).isEqualTo("## 설정\n설정 본문\n");

        var saved = writes.update(page.id(), 1, null, "## 설정\n바뀐 설정", null, "섹션 편집", 2, null, false, 1, "web");
        assertThat(saved.version()).isEqualTo(2);
        assertThat(writes.source(page.id(), null).content())
                .isEqualTo("# 개요\n개요 본문\n## 설정\n바뀐 설정\n# 로드맵\n로드맵 본문\n");

        assertThatThrownBy(() -> writes.update(page.id(), 1, null, "x", null, null, null, null, false, 1, "web"))
                .isInstanceOf(WikiException.class)
                .satisfies(e -> {
                    WikiException w = (WikiException) e;
                    assertThat(w.code()).isEqualTo("VERSION_CONFLICT");
                    assertThat(w.details()).containsEntry("currentVersion", 2);
                });
        assertThat(jdbc.sql("SELECT count(*) FROM page_revisions WHERE page_id = ?").param(page.id())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void draftPublishKeepsAttachmentsAndStaleDraftsAreCleaned() {
        long draft = writes.createDraft(1);
        var att = attachments.upload(draft, "image.png", "image/png", "PNG".getBytes(), 1);
        assertThat(jdbc.sql("SELECT count(*) FROM pages WHERE is_draft = 0").query(Integer.class).single()).isZero();

        var saved = writes.update(draft, 1, "새 문서", "![](" + att.url() + ")", null, null, null, "tip/새 문서", true, 1, "web");
        assertThat(saved.path()).isEqualTo("tip/새 문서");
        assertThat(saved.warnings()).isEmpty();
        assertThat(attachments.list(draft).get(0).referenced()).isTrue();

        long stale = writes.createDraft(1);
        var staleAtt = attachments.upload(stale, "a.txt", null, "x".getBytes(), 1);
        jdbc.sql("UPDATE pages SET created_at = '2000-01-01T00:00:00Z' WHERE id = ?").param(stale).update();
        assertThat(writes.deleteStaleDrafts(Duration.ofHours(24))).isEqualTo(1);
        assertThat(storage.pathOf(stale, staleAtt.id())).doesNotExist();
        assertThat(jdbc.sql("SELECT count(*) FROM pages WHERE id = ?").param(draft).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void attachmentNamesRenameCopyDeleteAndWarnings() {
        var page = writes.create("doc", null, "", null, null, false, 1, "web");
        var first = attachments.upload(page.id(), "화면 1.png", "image/png", "A".getBytes(), 1);
        var second = attachments.upload(page.id(), "C:\\temp\\화면 1.png", "image/png", "B".getBytes(), 1);
        assertThat(second.filename()).isEqualTo("화면 1-1.png");
        assertThat(attachments.upload(page.id(), "", null, "C".getBytes(), 1).filename()).isEqualTo("file");
        assertThatThrownBy(() -> attachments.upload(page.id(), "big.bin", null, new byte[2 * 1024 * 1024], 1))
                .extracting("code").isEqualTo("TOO_LARGE");

        writes.update(page.id(), 1, null, "![](" + first.url() + ") ![](/p/999/files/x.png) ![](/p/" + page.id()
                + "/files/none.png)", null, null, null, null, false, 1, "web");
        assertThat(writes.warnings(page.id())).containsExactly("FOREIGN_ATTACHMENT:999/x.png", "MISSING_ATTACHMENT:none.png");

        var renamed = attachments.rename(page.id(), first.id(), "결과.png", true, 1, "web");
        assertThat(renamed.pageVersion()).isEqualTo(3);
        assertThat(writes.source(page.id(), null).content()).contains(AttachmentStorage.url(page.id(), "결과.png"));
        assertThatThrownBy(() -> attachments.rename(page.id(), second.id(), "결과.png", false, 1, "web"))
                .extracting("code").isEqualTo("NAME_EXISTS");

        var other = writes.create("other", null, "", null, null, false, 1, "web");
        var copied = attachments.copy(other.id(), page.id(), "결과.png", 1);
        assertThat(new String(storage.read(other.id(), copied.id()))).isEqualTo("A");

        attachments.delete(page.id(), second.id());
        assertThat(storage.pathOf(page.id(), second.id())).doesNotExist();
        assertThat(attachments.list(page.id())).extracting(AttachmentService.Info::filename).containsExactly("file", "결과.png");
    }
}
