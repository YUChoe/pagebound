package io.github.yuchoe.pagebound.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI 문서 GET /api/docs (spec/features/F6-mcp-api.md 5절). 인증: 웹 세션 또는 Bearer 토큰.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pageboundOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Pagebound REST API").version("v1")
                        .description("웹 세션(쓰기는 X-XSRF-TOKEN 필요) 또는 Authorization: Bearer pb_... 토큰으로 인증. "
                                + "에이전트는 MCP 엔드포인트 /mcp 사용을 권장."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
