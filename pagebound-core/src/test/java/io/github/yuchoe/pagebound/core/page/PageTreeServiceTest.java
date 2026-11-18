package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.yuchoe.pagebound.core.CoreFixture;
import io.github.yuchoe.pagebound.core.WikiException;

class PageTreeServiceTest {

    @TempDir
    Path dir;
    CoreFixture f;

    @BeforeEach
    void setUp() {
        f = new CoreFixture(dir);
    }

    @AfterEach
    void close() {
        f.close();
    }

    private Integer target(long source, String path) {
        return f.jdbc.sql("SELECT target_page_id FROM page_links WHERE source_page_id = ? AND target_path = ?")
                .params(source, path).query((rs, n) -> (Integer) rs.getObject(1)).list().getFirst();
    }

    @Test
    void moveRewritesBacklinksRelativeLinksAndAddsAliases() {
        long stat = f.page("nems/통계", "[[../형제]] [[/하위]] [[nems/형제#개요|형제 문서]]");
        f.page("nems/통계/하위", "하위");
        long sibling = f.page("nems/형제", "형제");
        long home = f.page("home", "[[nems/통계]] [[nems/통계/하위|하위 링크]] `[[nems/통계]]`");
        f.page("archive", "");

        var moved = f.tree.move(stat, "archive", null, "통계 보관", null, 1, "web");
        assertThat(moved.path()).isEqualTo("archive/통계");
        assertThat(f.path(stat)).isEqualTo("archive/통계");
        assertThat(f.jdbc.sql("SELECT path FROM pages WHERE path LIKE 'archive/통계/%'").query(String.class).list())
                .containsExactly("archive/통계/하위");
        // 다른 문서의 링크는 새 경로로, 코드 안 텍스트는 그대로
        assertThat(f.content(home)).isEqualTo("[[archive/통계]] [[archive/통계/하위|하위 링크]] `[[nems/통계]]`");
        // 이동한 문서의 상대 링크 ../형제 는 원래 대상(nems/형제)을 유지하도록 절대 경로로, /하위 는 그대로 유효
        assertThat(f.content(stat)).isEqualTo("[[nems/형제]] [[/하위]] [[nems/형제#개요|형제 문서]]");
        assertThat(target(stat, "nems/형제")).isEqualTo((int) sibling);
        assertThat(moved.linkUpdatedPages()).isEqualTo(1);
        // 이전 경로 alias
        assertThat(f.pages.findIdByAlias("nems/통계")).contains(stat);
        assertThat(f.jdbc.sql("SELECT action FROM page_revisions WHERE page_id = ? ORDER BY version DESC LIMIT 1")
                .param(home).query(String.class).single()).isEqualTo("link-updated");
    }

    @Test
    void moveRejectsCycleExistingPathAndReorders() {
        long a = f.page("a", "");
        f.page("a/b", "");
        long c = f.page("c", "");
        long d = f.page("d", "");
        assertThatThrownBy(() -> f.tree.move(a, "a/b", null, null, null, 1, "web"))
                .isInstanceOf(WikiException.class).extracting("code").isEqualTo("CYCLE");
        assertThatThrownBy(() -> f.tree.move(c, "", "d", null, null, 1, "web"))
                .extracting("code").isEqualTo("PATH_EXISTS");

        f.tree.move(d, null, null, null, 0, 1, "web");
        assertThat(f.jdbc.sql("SELECT path FROM pages WHERE parent_id IS NULL ORDER BY sort_order").query(String.class).list())
                .containsExactly("d", "a", "c");
        assertThat(f.jdbc.sql("SELECT version FROM pages WHERE id = ?").param(d).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void trashRestorePurge() {
        long parent = f.page("p", "부모");
        long child = f.page("p/c", "자식");
        f.writes.replaceContent(child, "![](/p/" + child + "/files/x.png)", null, 1, "web");
        f.jdbc.sql("INSERT INTO attachments (page_id, filename, mime, size, sha256, created_at) VALUES (?, 'x.png', 'image/png', 1, 's', 't')")
                .param(child).update();
        f.storage.write(child, 1, "x".getBytes());
        long home = f.page("home", "[[p]]");

        assertThatThrownBy(() -> f.tree.trash(parent, false, 1)).extracting("code").isEqualTo("HAS_CHILDREN");
        assertThat(f.tree.trash(parent, true, 1)).isEqualTo(2);
        assertThat(target(home, "p")).isNull();
        assertThat(f.tree.trashList(30)).singleElement().satisfies(item -> {
            assertThat(item.path()).isEqualTo("p");
            assertThat(item.pageCount()).isEqualTo(2);
            assertThat(item.deletedBy()).isEqualTo("편집자");
        });

        long occupant = f.page("p", "새 문서");
        assertThatThrownBy(() -> f.tree.restore(parent, null, 1)).extracting("code").isEqualTo("PATH_EXISTS");
        assertThat(f.tree.restore(parent, "p-복원", 1)).isEqualTo("p-복원");
        assertThat(f.path(child)).isEqualTo("p-복원/c");

        f.tree.trash(occupant, false, 1);
        assertThat(f.tree.purge(occupant, 1L)).isEqualTo(1);
        f.tree.trash(parent, true, 1);
        assertThat(f.tree.purge(parent, 1L)).isEqualTo(2);
        assertThat(f.storage.pathOf(child, 1)).doesNotExist();
        assertThat(f.jdbc.sql("SELECT action FROM audit_log ORDER BY id").query(String.class).list())
                .containsExactly("PAGE_TRASH", "PAGE_RESTORE", "PAGE_TRASH", "PAGE_PURGE", "PAGE_TRASH", "PAGE_PURGE");
    }

    @Test
    void purgeExpiredRemovesOnlyOldTrash() {
        long old = f.page("old", "");
        long recent = f.page("recent", "");
        f.tree.trash(old, false, 1);
        f.tree.trash(recent, false, 1);
        f.jdbc.sql("UPDATE pages SET deleted_at = '2000-01-01T00:00:00Z' WHERE id = ?").param(old).update();
        assertThat(f.tree.purgeExpired(30)).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT id FROM pages").query(Long.class).list()).containsExactly(recent);
    }

    @Test
    void tagAdmin() {
        f.page("a", "", "Java", "백엔드");
        f.page("b", "", "java", "프런트");
        f.page("c", "", "백엔드");
        var java = f.tags.list("java", 10, false).getFirst();
        var backend = f.tags.list("백엔드", 10, false).getFirst();
        assertThat(java.count()).isEqualTo(2);
        assertThat(f.tags.pagesWithAll(List.of("java", "백엔드"))).extracting(TagStoreAccess::path).containsExactly("a");

        f.tags.rename(java.id(), "JVM", 1);
        assertThatThrownBy(() -> f.tags.rename(java.id(), "백엔드", 1)).extracting("code").isEqualTo("NAME_EXISTS");
        assertThatThrownBy(() -> f.tags.delete(java.id(), 1)).extracting("code").isEqualTo("TAG_IN_USE");
        f.tags.merge(java.id(), backend.id(), 1);
        assertThat(f.tags.list("", 10, true)).extracting(t -> t.name() + t.count()).containsExactly("백엔드3", "프런트1");
    }

    /** TaggedPage 경로 추출용 */
    private interface TagStoreAccess {
        static String path(io.github.yuchoe.pagebound.core.tag.TagStore.TaggedPage p) {
            return p.path();
        }
    }
}
