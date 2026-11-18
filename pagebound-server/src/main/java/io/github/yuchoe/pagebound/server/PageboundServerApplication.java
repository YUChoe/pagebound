package io.github.yuchoe.pagebound.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Pagebound 웹 서버 진입점.
 */
@SpringBootApplication(scanBasePackages = "io.github.yuchoe.pagebound")
public class PageboundServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PageboundServerApplication.class, args);
    }
}
