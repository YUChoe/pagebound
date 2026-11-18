# 01. 아키텍처

## 1. 핵심 결정

### D1. 기존 DB 그대로 사용 vs 마이그레이션 → 신규 스키마로 1회 마이그레이션

| 기준 | 그대로 사용 | 마이그레이션 |
|------|------------|-------------|
| R2 페이지 종속 | `assets`에 pageId 없음 | `attachments.page_id NOT NULL` + CASCADE |
| R3 트리 | path 문자열 기반, `pageTree` 캐시 동기화 필요 | `parent_id` 인접 리스트 |
| 불필요 테이블 | 31개 중 약 20개 미사용 | 9개 테이블 |

원본 `database.sqlite`는 읽기 전용으로 보존하고, 마이그레이터로 `pagebound.db` + 첨부 디렉토리를 생성한다. 상세: [F8](features/F8-migration.md)

### D2. DB 엔진 → SQLite 유지 (WAL 모드)

사용자 13명, 문서 약 200개 규모에서 충분. 단일 파일 백업.

### D3. 본문 포맷 → Markdown 유지 + 위키 링크 `[[ ]]` 확장

기존 168페이지 전부 markdown. WYSIWYG 포맷 전환 시 변환 손실 위험.

### D4. 첨부 저장 → 파일시스템 + DB 메타데이터

- 경로: `data/attachments/{page_id}/{attachment_id}`
- DB 비대화 방지, HTTP Range 스트리밍 용이

### D5. 서버 언어 → Java 21 + Spring Boot (Q1 확정)

| 기준 | Java 21 + Spring Boot | TypeScript (Node) | Go |
|------|----------------------|-------------------|----|
| 유지보수 주체 적합성 | 높음. 위키 문서 107/168이 Java 기반 nems 관련, JDK 21 환경 보유 | 팀 숙련도 미확인 | 팀 숙련도 미확인 |
| MCP 서버 | 공식 MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`): 서블릿 Streamable HTTP 트랜스포트, 요청별 `contextExtractor`, `addToolFilter` | 공식 TS SDK, 가장 성숙 | 공식 Go SDK |
| MCP 인증 | Spring Security Bearer 필터 + `api_tokens` (직접 구현, 트랜스포트는 인증 미제공) | 직접 구현 | 직접 구현 |
| 기존 bcrypt `$2a$` | `BCryptPasswordEncoder` 그대로 | bcryptjs | x/crypto/bcrypt |
| 서버 측 마크다운 파싱 | flexmark-java (CommonMark AST) | markdown-it (클라이언트와 동일 엔진) | goldmark |
| 프론트엔드와 언어 통일 | 불가 | 가능 | 불가 |
| 배포 | 단일 jar | Node 런타임 + node_modules | 단일 바이너리 |

결정 근거:
1. 운영·수정 주체가 Java 팀이다
2. 공식 MCP Java SDK가 서블릿 Streamable HTTP 트랜스포트와 요청별 도구 필터를 제공한다 (Context7 확인). Spring AI는 사용하지 않는다 (LLM 기능 없음, Spring Boot 버전 결합 회피)
3. HTML 렌더링은 클라이언트에서 하므로 서버는 AST 파싱(링크 추출, 섹션 분할, 목차)만 필요하다. TS 선택 시 "렌더러 통일" 이점이 작다

리스크: 서버(flexmark)와 클라이언트(markdown-it)의 헤딩 경계 해석 차이 → 둘 다 CommonMark 준수. 전체 문서 대상 섹션 분할 비교 테스트로 검증 (M2).

## 2. 기술 스택

| 영역 | 선택 |
|------|------|
| Backend | Java 21, Spring Boot 4.1.2 (M1 확정), Maven |
| DB 접근 | Spring JDBC (JdbcClient) + xerial sqlite-jdbc 3.53.4.0, Flyway 12.4.0 (두 버전 모두 Boot 관리 버전, SQLite 지원은 `flyway-core` 내장) |
| 마크다운 파싱 (서버) | flexmark-java 0.64.8: 링크 추출, 섹션 분할, 목차, FTS용 평문 추출 |
| MCP | MCP Java SDK 2.2.0 (`mcp-bom`, JSON은 Boot 4의 Jackson 3에 맞춰 `mcp-json-jackson3`). `HttpServletStreamableServerTransportProvider`를 서블릿 빈으로 등록, 엔드포인트 `/mcp`, `McpServer.sync` + 빌더로 도구 등록 |
| 인증 | Spring Security 폼 로그인(웹) + Bearer 토큰(MCP/REST) |
| Frontend | Vue 3 + Vite (SPA), Spring Boot 정적 리소스로 서빙 |
| 에디터 | CodeMirror 6 + 실시간 미리보기 |
| 렌더러 (클라이언트) | markdown-it + footnote + task-lists, 위키 링크 커스텀 플러그인, DOMPurify, highlight.js, mermaid |
| HTML→MD | turndown |
| 검색 | SQLite FTS5 `trigram` 토크나이저, 2자 이하는 LIKE ([F11](features/F11-search.md)) |
| 외부 HTTP | Spring `RestClient` (GitLab API, OpenGraph 수집) |
| HTML 파싱 (OG) | jsoup (`<head>`만) |

## 3. 모듈 구성

```
team-wiki/
├── pom.xml               부모 POM
├── pagebound-core/       도메인, 저장소(JDBC), 마크다운 분석(flexmark), 서비스
├── pagebound-server/     Spring Boot 앱: 웹 API, 보안, MCP, 링크 미리보기, 정적 리소스
├── pagebound-migrator/   마이그레이션 CLI (pagebound-core 재사용)
├── web/                  Vue 3 + Vite. 빌드 결과를 pagebound-server 정적 리소스로 포함
└── spec/
```

- 웹 UI, REST API, MCP 도구는 모두 `pagebound-core` 서비스 계층을 호출한다 (로직 중복 금지)

## 4. 비기능

| 항목 | 사양 |
|------|------|
| 배포 | 단일 jar + `data/` 디렉토리, 기존 `<legacy-wiki>` 대체 (Q8 확정) |
| 백업 | `pagebound.db`(SQLite online backup) + `data/attachments/` 일 1회 |
| 보안 | 마크다운 HTML sanitize, CSRF(웹), 토큰 해시 저장, MCP 엔드포인트 미인증 접근 차단, 링크 미리보기 SSRF 방어 ([F10](features/F10-link-preview.md) 6절), GitLab 토큰은 외부 설정으로만 주입 |
| 로그 | MCP 도구 호출 로그 (토큰, 도구명, 대상 경로, 결과 코드) |
| 성능 | 최대 문서(396KB) 렌더 1초 이내, 트리 API 200ms 이내 |
