# F7. 공통 기능: 역사, 권한, 사용자

관련: [F11 검색](F11-search.md), 단계 [M2](../phases/M2-read.md) (로그인, 권한), [M7](../phases/M7-history-p1.md) (역사, 관리자)

## 1. 검색

[F11-search.md](F11-search.md)로 이관 (R10).

## 2. 역사

- 목록: 버전, 편집자, source 배지(웹/에이전트/시스템/이전), 편집 요약, ±bytes, 시각
- 두 버전 diff (side-by-side, 줄 단위)
- 특정 버전으로 복원 → 새 버전 `action='restored'`
- 행별 blame: [F13](F13-blame.md)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/pages/{id}/revisions` | 목록 |
| GET | `/api/v1/pages/{id}/revisions/{version}` | 특정 버전 |
| GET | `/api/v1/pages/{id}/diff?from=&to=` | diff |
| POST | `/api/v1/pages/{id}/revisions/{version}/restore` | 복원 |

## 3. 권한

| role | 권한 |
|------|------|
| ADMIN | 전체 + 관리자 기능 ([F12](F12-admin.md)) |
| EDITOR | 문서/첨부 CRUD, 이동, 휴지통 이동, 본인 토큰 |
| VIEWER | 읽기, 본인 토큰(read만) |
| 익명 | 설정 `pagebound.anonymous-read=true`일 때 읽기 (기존 Guests 그룹 = 허용, Q7 확정: 유지) |

## 4. 사용자

- 관리자가 생성 (자가 가입 없음). 생성·역할·비활성화·비밀번호 초기화는 [F12](F12-admin.md) 5절
- 본인 비밀번호 변경, API 토큰 발급/폐기 ([F6](F6-mcp-api.md))
- 로그인: Spring Security 폼 로그인, 기존 bcrypt `$2a$` 해시 그대로 검증

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/login`, `/logout` | 세션 |
| GET/POST/PATCH | `/api/v1/admin/users` | 관리자 ([F12](F12-admin.md) 14절) |
| POST | `/api/v1/me/password` | 비밀번호 변경 |

## 5. 범위 제외

댓글, 다국어, SSO, 문서 단위 권한, 실시간 공동 편집, Git 동기화.

## 6. 완료 기준

| 항목 | 기준 |
|------|------|
| 복원 | 복원 후 본문 = 해당 버전 본문 |
| 권한 | VIEWER의 PUT 요청 403, 익명 읽기 off 시 로그인 페이지 리다이렉트 |
| 로그인 | 마이그레이션 사용자 기존 비밀번호로 로그인 |
