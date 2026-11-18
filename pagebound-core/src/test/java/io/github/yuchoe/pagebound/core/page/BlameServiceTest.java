package io.github.yuchoe.pagebound.core.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.yuchoe.pagebound.core.CoreFixture;
import io.github.yuchoe.pagebound.core.page.BlameService.Hunk;

class BlameServiceTest {

    @TempDir
    Path dir;
    CoreFixture f;
    BlameService blame;

    @BeforeEach
    void setUp() {
        f = new CoreFixture(dir);
        f.jdbc.sql("INSERT INTO users (id, email, name, password_hash, role, created_at, updated_at) "
                + "VALUES (2, 'b@b.c', '두번째', 'x', 'EDITOR', 't', 't')").update();
        blame = new BlameService(f.jdbc);
    }

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void attributesEachLineToLastChangingRevisionAndAuthor() {
        long id = f.writes.create("doc", null, "a\nb\nc", null, null, false, 1, "web").id();
        f.writes.update(id, 1, null, "a\nB\nc\nd", null, null, null, null, false, 2, "web");
        f.writes.update(id, 2, null, "x\na\nB\nc\nd", null, null, null, null, false, 1, "mcp");

        var result = blame.blame(id);
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.hunks()).containsExactly(
                new Hunk(1, 1, 3), new Hunk(2, 1, 1), new Hunk(3, 1, 2), new Hunk(4, 1, 1), new Hunk(5, 1, 2));
        assertThat(result.revisions().get(2).author()).isEqualTo("두번째");
        assertThat(result.revisions().get(3).source()).isEqualTo("mcp");
    }

    @Test
    void incrementalUpdateEqualsFullRecompute() {
        long id = f.writes.create("doc", null, "1\n2\n3\n4\n5", null, null, false, 1, "web").id();
        f.writes.update(id, 1, null, "1\n2\n삼\n4\n5", null, null, null, null, false, 2, "web");
        blame.blame(id); // v2 캐시
        f.writes.update(id, 2, null, "0\n1\n2\n삼\n5\n6", null, null, null, null, false, 1, "web");

        List<Hunk> incremental = blame.blame(id).hunks();
        assertThat(f.jdbc.sql("SELECT version FROM page_blame WHERE page_id = ?").param(id).query(Integer.class).single())
                .isEqualTo(3);
        f.jdbc.sql("DELETE FROM page_blame").update();
        assertThat(blame.blame(id).hunks()).isEqualTo(incremental);
        assertThat(incremental).containsExactly(new Hunk(1, 1, 3), new Hunk(2, 2, 1), new Hunk(4, 1, 2),
                new Hunk(5, 1, 1), new Hunk(6, 1, 3));
    }

    @Test
    void runLengthEncoding() {
        List<Integer> lines = List.of(1, 1, 3, 3, 3, 2);
        assertThat(BlameService.encode(lines)).isEqualTo("1*2,3*3,2*1");
        assertThat(BlameService.decode("1*2,3*3,2*1")).isEqualTo(lines);
    }
}
