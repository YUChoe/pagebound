package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.yuchoe.pagebound.core.Actor;
import io.github.yuchoe.pagebound.core.CoreFixture;
import io.github.yuchoe.pagebound.core.page.BlameService.Hunk;
import io.github.yuchoe.pagebound.core.page.HistoryService.DiffRow;

class HistoryServiceTest {

    @TempDir
    Path dir;
    CoreFixture f;
    HistoryService history;

    @BeforeEach
    void setUp() {
        f = new CoreFixture(dir);
        history = new HistoryService(f.jdbc, f.pages);
    }

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void restoreCreatesRestoredRevisionWithOldContent() {
        long id = f.writes.create("doc", "제목1", "a\nb", null, null, false, Actor.web(1)).id();
        f.writes.update(id, 1, "제목2", "a\nB\nc", null, null, null, null, false, Actor.web(1));
        var saved = f.writes.restore(id, 1, Actor.web(1));
        assertThat(saved.version()).isEqualTo(3);
        assertThat(f.content(id)).isEqualTo("a\nb");
        var revisions = history.revisions(id, 10);
        assertThat(revisions.getFirst()).satisfies(r -> {
            assertThat(r.action()).isEqualTo("restored");
            assertThat(r.title()).isEqualTo("제목1");
            assertThat(r.summary()).isEqualTo("v1 복원");
            assertThat(r.deltaBytes()).isEqualTo(-2);
        });
    }

    @Test
    void diffRowsPairChangesAndKeepLineNumbers() {
        long id = f.writes.create("doc", null, "a\nb\nc\nd", null, null, false, Actor.web(1)).id();
        f.writes.update(id, 1, null, "a\nB\nc\nd\ne", null, null, null, null, false, Actor.web(1));
        assertThat(history.diff(id, 1, 2).rows()).containsExactly(
                new DiffRow("equal", 1, 1, "a", "a"),
                new DiffRow("change", 2, 2, "b", "B"),
                new DiffRow("equal", 3, 3, "c", "c"),
                new DiffRow("equal", 4, 4, "d", "d"),
                new DiffRow("insert", null, 5, null, "e"));
        assertThat(history.diff(id, 0, 1).rows()).extracting(DiffRow::type).containsOnly("insert");
    }

    @Test
    void blameIgnoresSameSizeSystemChanges() {
        long target = f.page("old", "");
        long id = f.page("doc", "x\n[[old]] 링크\ny");
        f.tree.move(target, null, "new", null, null, Actor.web(1)); // doc v2 = link-updated
        var blame = new BlameService(f.jdbc).blame(id);
        assertThat(f.content(id)).isEqualTo("x\n[[new]] 링크\ny");
        assertThat(blame.hunks()).containsExactly(new Hunk(1, 3, 1));
    }
}
