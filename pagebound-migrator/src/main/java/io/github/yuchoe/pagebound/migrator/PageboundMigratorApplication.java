package io.github.yuchoe.pagebound.migrator;

import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Wiki.js 마이그레이션 CLI 진입점. 웹 서버 없이 실행된다.
 * 사용법: java -jar pagebound-migrator.jar --source database.sqlite --target data/ [--force]
 * 종료 코드: 0 = 검증 전부 통과, 1 = 검증 실패, 2 = 실행 오류
 */
@SpringBootApplication(scanBasePackages = "io.github.yuchoe.pagebound")
public class PageboundMigratorApplication {

    public static void main(String[] args) {
        int code;
        try {
            MigrationOptions options = MigrationOptions.parse(args);
            options.prepareTarget();

            SpringApplication app = new SpringApplication(PageboundMigratorApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setDefaultProperties(Map.of(
                    "pagebound.data-dir", options.target().toString(),
                    "spring.datasource.url", "jdbc:sqlite:" + options.dbFile()
                            + "?foreign_keys=true&journal_mode=WAL&busy_timeout=5000",
                    "spring.datasource.hikari.maximum-pool-size", "1"));
            try (ConfigurableApplicationContext ctx = app.run()) {
                boolean passed = ctx.getBean(Migrator.class).run(options);
                System.out.println((passed ? "검증 통과" : "검증 실패") + " — 리포트: " + options.reportFile());
                code = passed ? 0 : 1;
            }
        } catch (RuntimeException e) {
            System.err.println("마이그레이션 실패: " + e.getMessage());
            e.printStackTrace();
            code = 2;
        }
        System.exit(code);
    }
}
