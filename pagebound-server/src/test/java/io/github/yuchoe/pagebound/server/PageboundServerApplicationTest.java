package io.github.yuchoe.pagebound.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 서버 컨텍스트가 SQLite + Flyway 자동 설정으로 기동되는지 확인한다.
 */
@SpringBootTest(properties = "pagebound.data-dir=target")
class PageboundServerApplicationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void contextLoadsAndSchemaMigrated() {
        Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = 1")
                .query(Integer.class)
                .single();
        assertThat(applied).isEqualTo(1);
    }
}
