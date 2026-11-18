package io.github.yuchoe.pagebound.server.mcp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.yuchoe.pagebound.core.WikiException;
import io.github.yuchoe.pagebound.server.security.TokenAuthentication;
import io.github.yuchoe.pagebound.server.security.TokenRateLimiter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 서버 (spec/features/F6-mcp-api.md). Streamable HTTP 서블릿 /mcp, Bearer 토큰 인증은 SecurityConfig가 담당.
 * 요청 스레드에서 토큰 인증 정보를 transport context로 옮기고, read 토큰에는 쓰기 도구를 노출하지 않는다.
 */
@Configuration
public class McpConfig {

    static final String AUTH = "auth";
    static final String BASE_URL = "baseUrl";
    private static final String ENDPOINT = "/mcp";
    private static final Logger log = LoggerFactory.getLogger("pagebound.mcp");

    static final String INSTRUCTIONS = """
            Pagebound 사내 개발 위키. 문서는 경로(path, 예: nems/통계)로 식별하고 영구 링크는 {base}/p/{id}.
            - 큰 문서는 get_outline으로 섹션 번호를 확인한 뒤 get_page(section) / update_page(section)으로 다룬다.
            - 수정은 항상 get_page로 받은 version을 base_version으로 보낸다. 불일치 시 VERSION_CONFLICT.
            - 위키 링크: [[경로]], [[경로#헤딩]], [[경로|표시 텍스트]], 상대 경로 [[../형제]], [[/하위]].
            - 첨부 URL: /p/{pageId}/files/{파일명} (문서 소유). upload_attachment가 돌려준 markdown을 본문에 넣는다.
            - 코드블록 속성: ```java title="Main.java" collapsed
            - 분류는 트리 경로(상위/하위 문서)와 태그로 한다.""";

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(JsonMapper jsonMapper,
                                                             @Value("${pagebound.site.base-url:}") String baseUrl) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> {
                    Map<String, Object> context = new HashMap<>();
                    if (SecurityContextHolder.getContext().getAuthentication() instanceof TokenAuthentication auth) {
                        context.put(AUTH, auth);
                    }
                    String base = baseUrl.isBlank()
                            ? request.getScheme() + "://" + request.getHeader("Host")
                            : baseUrl.replaceAll("/+$", "");
                    context.put(BASE_URL, base);
                    return McpTransportContext.create(context);
                })
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, ENDPOINT);
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport, McpReadTools readTools,
                            McpWriteTools writeTools, TokenRateLimiter rateLimiter, JsonMapper jsonMapper) {
        List<McpTool> tools = Stream.concat(readTools.tools().stream(), writeTools.tools().stream()).toList();
        Map<String, Boolean> writeByName = new HashMap<>();
        tools.forEach(t -> writeByName.put(t.name(), t.write()));
        return McpServer.sync(transport)
                .serverInfo("pagebound", "0.1.0")
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools.stream().map(t -> spec(t, rateLimiter, jsonMapper)).toList())
                .addToolFilter((context, tool) -> !writeByName.getOrDefault(tool.name(), true)
                        || context.get(AUTH) instanceof TokenAuthentication auth && auth.canWrite())
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification spec(McpTool tool, TokenRateLimiter rateLimiter,
                                                                JsonMapper jsonMapper) {
        McpSchema.Tool schema = McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                .description(tool.description())
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(!tool.write()).build())
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(schema)
                .callHandler((exchange, request) -> call(tool, exchange.transportContext(), request.arguments(),
                        rateLimiter, jsonMapper))
                .build();
    }

    static McpSchema.CallToolResult call(McpTool tool, McpTransportContext context, Map<String, Object> arguments,
                                         TokenRateLimiter rateLimiter, JsonMapper jsonMapper) {
        long started = System.nanoTime();
        String code = "OK";
        TokenAuthentication auth = context.get(AUTH) instanceof TokenAuthentication a ? a : null;
        try {
            if (auth == null) {
                throw new WikiException(401, "UNAUTHORIZED", "token required");
            }
            if (tool.write() && !auth.canWrite()) {
                throw new WikiException(403, "FORBIDDEN", "write scope and EDITOR role required");
            }
            if (!rateLimiter.tryAcquire(auth.token().tokenId())) {
                throw new WikiException(429, "RATE_LIMITED",
                        "rate limit exceeded: " + TokenRateLimiter.PER_MINUTE + " calls per minute");
            }
            Object result = tool.handler().handle(new McpTool.Args(arguments),
                    new McpTool.Caller(auth, (String) context.get(BASE_URL)));
            if (result instanceof McpSchema.CallToolResult direct) {
                return direct;
            }
            return McpSchema.CallToolResult.builder().addTextContent(jsonMapper.writeValueAsString(result))
                    .isError(false).build();
        } catch (WikiException e) {
            code = e.code();
            return error(jsonMapper, e.code(), e.getMessage(), e.details());
        } catch (Exception e) {
            code = "INTERNAL";
            log.warn("mcp tool {} failed", tool.name(), e);
            return error(jsonMapper, "INTERNAL", e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
        } finally {
            log.info("tool={} token={} user={} code={} ms={}", tool.name(),
                    auth == null ? null : auth.token().tokenId(), auth == null ? null : auth.getName(), code,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 오류 결과: 사람이 읽을 message + 기계 판별 code. 현재 본문 같은 큰 상세는 제외한다. */
    private static McpSchema.CallToolResult error(JsonMapper jsonMapper, String code, String message,
                                                  Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        details.forEach((k, v) -> {
            if (!k.equals("currentContent")) {
                body.put(k, v);
            }
        });
        return McpSchema.CallToolResult.builder().addTextContent(jsonMapper.writeValueAsString(body)).isError(true)
                .build();
    }
}
