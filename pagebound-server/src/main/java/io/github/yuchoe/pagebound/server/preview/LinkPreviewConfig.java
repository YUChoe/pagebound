package io.github.yuchoe.pagebound.server.preview;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 링크 미리보기 설정 바인딩.
 */
@Configuration
@EnableConfigurationProperties(LinkPreviewProperties.class)
public class LinkPreviewConfig {
}
