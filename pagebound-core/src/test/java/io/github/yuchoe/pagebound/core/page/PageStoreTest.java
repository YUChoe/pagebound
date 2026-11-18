package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.github.yuchoe.pagebound.core.attachment.AttachmentStorage;

/**
 * 문서 생성(경로 계산), 리비전, 링크 인덱스, 첨부 저장.
 */
class PageStoreTest {

    @TempDir
    Path tempDir;

    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private PageStore pages;
    private LinkIndexer links;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tempDir.resolve("t.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, null, null).load().migrate();
        dataSource = new SingleConnectionDataSource(url, true);
        jdbc = JdbcClient.create(dataSource);
        pages = new PageStore(jdbc);
        links = new LinkIndexer(jdbc, pages);
    }

    @AfterEach
    void closeConnection() {
        // Windows는 열린 SQLite 파일을 삭제할 수 없으므로 @TempDir 정리 전에 닫는다
        dataSource.destroy();
    }

    private long page(Long parentId, String slug, String content) {
        return pages.insert(new PageStore.NewPage(null, parentId, slug, slug, content, 0, 1, null, null, "t", "t"));
    }

    @Test
    void insertComputesPathFromParent() {
        long nems = page(null, "nems", "");
        long stat = page(nems, "통계", "");
        assertThat(pages.pathOf(stat)).contains("nems/통계");
        assertThatThrownBy(() -> page(nems, "a/b", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page(999L, "x", "")).isInstanceOf(IllegalArgumentException.class);

        pages.insertRevision(new PageStore.NewRevision(stat, 1, "통계", "nems/통계", "", "created", "web",
                null, null, null, "t"));
        assertThat(jdbc.sql("SELECT count(*) FROM page_revisions").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void linkIndexResolvesPathAliasAndRedLink() {
        long nems = page(null, "nems", "");
        long stat = page(nems, "통계", "");
        pages.insertAlias("old/stat", stat);
        long home = page(null, "home", "[[nems/통계]] [[old/stat]] [[없는문서]] [[/하위]]\n`[[ -f x ]]`");

        links.rebuild(home);
        List<Map<String, Object>> rows = jdbc.sql(
                "SELECT target_path, target_page_id FROM page_links WHERE source_page_id = ? ORDER BY target_path")
                .param(home).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("target_path"))
                .containsExactly("home/하위", "nems/통계", "old/stat", "없는문서");
        assertThat(rows).extracting(r -> r.get("target_page_id"))
                .containsExactly(null, (int) stat, (int) stat, null);
    }

    @Test
    void attachmentStorageWritesUnderPageDirectory() {
        AttachmentStorage storage = new AttachmentStorage(tempDir);
        byte[] data = "png".getBytes();
        storage.write(7, 3, data);
        assertThat(storage.pathOf(7, 3)).isEqualTo(tempDir.resolve("attachments").resolve("7").resolve("3"));
        assertThat(AttachmentStorage.sha256(storage.read(7, 3))).isEqualTo(AttachmentStorage.sha256(data));
        assertThat(AttachmentStorage.url(7, "화면 캡처.png")).isEqualTo("/p/7/files/%ED%99%94%EB%A9%B4%20%EC%BA%A1%EC%B2%98.png");
    }
}
