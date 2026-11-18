# F6. 에이전트 연동: MCP, API 토큰, REST API (R6)

관련: [01 아키텍처 D5](../01-architecture.md), [02 api_tokens](../02-data-model.md), 단계 [M5](../phases/M5-mcp.md)

## 1. 구성

| 항목 | 사양 |
|------|------|
| 프로토콜 | MCP Streamable HTTP, 엔드포인트 `/mcp` (MCP Java SDK `HttpServletStreamableServerTransportProvider`, Spring AI 미사용) |
| 인증 | `Authorization: Bearer <token>`. 사용자 설정 화면에서 발급, 발급 시 1회만 표시 |
| 권한 | 토큰 scope `read` / `write`. 실제 권한 = min(scope, 사용자 role). `read` 토큰에는 쓰기 도구 목록 자체를 노출하지 않음 (`contextExtractor`로 토큰 scope 추출 → `addToolFilter`) |
| 감사 | 모든 쓰기는 `page_revisions.source='mcp'`, `token_id` 기록. 역사 화면에 "에이전트" 배지 |
| 제한 | 토큰당 분당 60회, 첨부 업로드 1건 10MB (base64 전송 고려) |
| 공유 로직 | MCP 도구, REST API, 웹 UI가 동일 서비스 계층 사용 |
| 접근 범위 | 사내망 한정 (Q2 확정) |

클라이언트 등록 예 (Claude Code):

```
claude mcp add --transport http pagebound http://<host>/mcp --header "Authorization: Bearer <token>"
```

## 2. 토큰 관리

- 토큰 형식: `pb_` + 32바이트 랜덤 base62. DB에는 SHA-256만 저장
- 사용자 설정 화면: 발급(이름, scope, 만료일 선택: 30일/90일/무기한), 목록(마지막 사용 시각), 폐기
- 관리자: 전체 토큰 목록, 강제 폐기, 호출 로그 ([F12](F12-admin.md) 9절)
- 사용자 비활성화 시 해당 사용자 토큰 전부 무효

## 3. MCP 도구

### 3.1 읽기

| 도구 | 입력 | 반환 |
|------|------|------|
| `search_pages` | `query`([F11](F11-search.md) 4절 문법), `tags[]?`, `path?`, `limit?=10` | 경로, 영구 링크, 제목, 태그, 스니펫, 일치 섹션 번호, 수정일 |
| `get_page` | `path`, `section?` (번호 `2.1` 또는 헤딩 텍스트), `max_chars?=40000` | 마크다운, 제목, 영구 링크(`{base-url}/p/{id}`), 태그, version, 수정일, 첨부 목록. 초과 시 잘린 본문 + 목차 + `truncated=true` |
| `get_outline` | `path` | 번호 매긴 헤딩 트리 + 섹션별 문자 수 |
| `list_children` | `path?`(없으면 루트), `depth?=1` | 하위 문서 경로·제목·하위 수 |
| `list_tags` | - | 태그, 사용 수 |
| `recent_changes` | `since?`, `limit?=20` | 경로, 편집자, source, 요약, 시각 |
| `get_backlinks` | `path` | 참조 문서 목록 |
| `get_history` | `path`, `limit?` | 버전, 편집자, 요약, 시각 |
| `get_revision` | `path`, `version` | 해당 버전 마크다운 |
| `get_attachment` | `path`, `filename` | 이미지 → MCP image content, 텍스트 계열(≤1MB) → text, 그 외 → 메타데이터 + 다운로드 URL |

### 3.2 쓰기 (scope `write`)

| 도구 | 입력 | 동작 |
|------|------|------|
| `create_page` | `parent_path?`, `title`, `slug?`, `content`, `tags[]?`, `summary?` | 생성 후 경로·version 반환 |
| `update_page` | `path`, `base_version`, 다음 중 하나: `content`(전체) / `section` + `content` / `edits[{old,new}]`(정확 일치 치환), `summary?`, `dry_run?` | version 불일치 시 오류 + 현재 version. `dry_run`은 diff만 반환 |
| `set_tags` | `path`, `add[]?`, `remove[]?` | |
| `move_page` | `path`, `new_parent_path?`, `new_slug?` | [F3](F3-tree-links.md) 4절 링크 갱신 포함 (Q5 확정: 허용) |
| `upload_attachment` | `path`, `filename`, `content_base64` 또는 `source_url` | 첨부 URL 반환 (본문 삽입은 `update_page`로) |
| `trash_page` | `path` | 휴지통 이동만 (Q5 확정: 허용) |

MCP 미제공: 영구 삭제, 복원, 사용자/토큰 관리, 태그 병합.

## 4. 설계 규칙

1. 쓰기 도구는 `base_version` 또는 대상 존재 확인으로 동시 편집 덮어쓰기를 막는다
2. 대형 문서 대응: `get_page` 기본 상한 + `get_outline` → 섹션 단위 조회·수정
3. `edits[]`의 `old`가 0회 또는 2회 이상 일치하면 전체 거부 (`EDIT_NOT_UNIQUE`)
4. 도구 description에 위키 링크 문법, 첨부 URL 형식, 분류 규칙, 코드블록 속성 문법(`title=`, `collapsed`, [F9](F9-code-blocks.md) 2절)을 명시
5. 오류는 사람이 읽을 메시지 + 기계 판별 코드: `VERSION_CONFLICT`, `NOT_FOUND`, `FORBIDDEN`, `TOO_LARGE`, `EDIT_NOT_UNIQUE`, `RATE_LIMITED`

## 5. REST API

- `/api/v1/**`: 각 기능 스펙의 API 절 참조. 웹 세션 또는 Bearer 토큰으로 인증
- OpenAPI 문서: `/api/docs`
- 원문 조회: `GET /w/{path}.md` (Bearer 또는 익명 읽기 허용 시 무인증)

## 6. 완료 기준

| 항목 | 기준 |
|------|------|
| 시나리오 | Claude Code에서 검색 → `get_outline` → 섹션 조회 → `update_page`(section) → `upload_attachment` → 본문 삽입 성공 |
| 권한 | read 토큰의 `tools/list`에 쓰기 도구 없음 |
| 충돌 | 오래된 `base_version`으로 수정 시 `VERSION_CONFLICT` |
| 감사 | 역사 화면에 에이전트 배지 + 토큰 이름 표시 |
| 미인증 | 토큰 없이 `/mcp` 호출 시 401 |
