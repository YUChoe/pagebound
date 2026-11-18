# M5. MCP, API 토큰, REST API

- 선행: M3 (M4와 병행 가능. `move_page`, `search_pages`는 M4 서비스 완료 후 연결)
- 예상: 2일
- 범위: [F6](../features/F6-mcp-api.md) 전체

## 작업

| # | 작업 | 검증 |
|---|------|------|
| 1 | `api_tokens` 서비스: 발급(1회 표시), 목록, 폐기, 만료, 마지막 사용 시각 | 폐기 토큰 401 |
| 2 | Bearer 인증 필터 (`/mcp`, `/api/v1/**`), 사용자 비활성 시 무효 | 미인증 `/mcp` 401 |
| 3 | 토큰 관리 화면 (사용자 설정, 관리자 전체 목록) | 시각 확인 |
| 4 | MCP 서버 설정 (`spring-ai-starter-mcp-server-webmvc`, STREAMABLE, `/mcp`), scope별 tool filter | read 토큰 `tools/list`에 쓰기 도구 없음 |
| 5 | 읽기 도구 10개 (`get_page` 상한/섹션, `get_outline`, `get_attachment` image content 포함) | 도구별 테스트 |
| 6 | 쓰기 도구 6개 (서비스 재사용, `base_version`, `edits[]` 유일성, `dry_run`), 오류 코드 | `VERSION_CONFLICT`, `EDIT_NOT_UNIQUE` 재현 |
| 7 | 토큰당 rate limit, MCP 호출 로그, 역사 화면 에이전트 배지 | 61번째 호출 `RATE_LIMITED` |
| 8 | REST API 정리 + OpenAPI `/api/docs` | 문서 접근 |

## 완료 기준

1. Claude Code에 `claude mcp add --transport http pagebound http://<host>/mcp --header "Authorization: Bearer <token>"` 등록 후 시나리오 성공: 검색 → `get_outline` → 섹션 조회 → `update_page`(section) → `upload_attachment` → 본문 삽입
2. [F6](../features/F6-mcp-api.md) 6절 완료 기준 전부 통과
