package io.github.yuchoe.pagebound.core;

/**
 * 쓰기 주체: 사용자, 경로(web·mcp·api·system), 토큰(MCP·API일 때). 리비전의 author_id·source·token_id로 기록된다.
 */
public record Actor(long userId, String source, Long tokenId) {

    public static Actor web(long userId) {
        return new Actor(userId, "web", null);
    }
}
