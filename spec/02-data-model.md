# 02. 데이터 모델

- DB: SQLite, Flyway `V1__init.sql`로 생성
- 연결 시 `PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;` 필수
- 첨부 파일 실체 삭제는 서비스 계층에서 DB 삭제와 함께 처리

## 1. 테이블 사용처

| 테이블 | 용도 | 관련 기능 |
|--------|------|----------|
| users | 사용자, role | F7 |
| pages | 문서 트리 + 본문 | F1, F3, F5 |
| page_revisions | 버전 이력, 작성 경로(source) | F1, F6, F7 |
| page_aliases | 레거시/이동 전 경로 리다이렉트 | F3, F8 |
| page_links | 역링크, 레드 링크, 이동 시 링크 갱신 | F3, F5 |
| attachments | 페이지 종속 첨부 | F2 |
| tags, page_tags | 분류 | F4 |
| api_tokens | MCP/REST 토큰 | F6 |
| pages_fts | 전문 검색 (P1에서 `search_index`로 대체, F11 8절) | F11 |
| link_previews | 외부 링크 미리보기 캐시 | F10 |
| audit_log, settings, mcp_call_log | 관리 작업 감사, 런타임 설정, MCP 호출 로그 (추가 예정, DDL은 F12 13절) | F12 |

## 2. DDL

```sql
CREATE TABLE users (
  id            INTEGER PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
  name          TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL CHECK (role IN ('ADMIN','EDITOR','VIEWER')),
  is_active     INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  last_login_at TEXT
);

CREATE TABLE pages (
  id          INTEGER PRIMARY KEY,
  parent_id   INTEGER REFERENCES pages(id) ON DELETE RESTRICT,
  slug        TEXT NOT NULL,                    -- 경로 세그먼트, 기본값 = 제목
  path        TEXT NOT NULL,                    -- 조상 slug 결합 캐시 (예: nems/고도화/통계)
  title       TEXT NOT NULL,
  content     TEXT NOT NULL DEFAULT '',
  sort_order  INTEGER NOT NULL DEFAULT 0,
  version     INTEGER NOT NULL DEFAULT 1,      -- 낙관적 잠금
  is_draft    INTEGER NOT NULL DEFAULT 0,      -- 붙여넣기용 선생성 초안 (F1)
  created_by  INTEGER REFERENCES users(id),
  updated_by  INTEGER REFERENCES users(id),
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  deleted_at  TEXT                              -- 휴지통
);
CREATE UNIQUE INDEX ux_pages_path ON pages (path) WHERE deleted_at IS NULL;
CREATE INDEX ix_pages_parent ON pages (parent_id, sort_order);
CREATE INDEX ix_pages_updated ON pages (updated_at DESC);

CREATE TABLE page_revisions (
  id         INTEGER PRIMARY KEY,
  page_id    INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  version    INTEGER NOT NULL,
  title      TEXT NOT NULL,
  path       TEXT NOT NULL,
  content    TEXT NOT NULL,
  action     TEXT NOT NULL CHECK (action IN ('created','updated','moved','restored','link-updated')),
  source     TEXT NOT NULL CHECK (source IN ('web','mcp','api','system','migration')),
  summary    TEXT,                              -- 편집 요약
  author_id  INTEGER REFERENCES users(id),
  token_id   INTEGER REFERENCES api_tokens(id), -- source가 mcp/api일 때
  created_at TEXT NOT NULL,
  UNIQUE (page_id, version)
);

CREATE TABLE page_aliases (
  path    TEXT PRIMARY KEY,
  page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE
);

CREATE TABLE page_links (
  source_page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  target_path    TEXT NOT NULL,                  -- 본문에 적힌 경로(정규화, 절대경로)
  target_page_id INTEGER REFERENCES pages(id) ON DELETE SET NULL, -- NULL이면 레드 링크
  PRIMARY KEY (source_page_id, target_path)
);
CREATE INDEX ix_page_links_target ON page_links (target_page_id);

CREATE TABLE attachments (
  id         INTEGER PRIMARY KEY,
  page_id    INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  filename   TEXT NOT NULL,
  mime       TEXT NOT NULL,
  size       INTEGER NOT NULL,
  sha256     TEXT NOT NULL,
  created_by INTEGER REFERENCES users(id),
  created_at TEXT NOT NULL,
  UNIQUE (page_id, filename)
);

CREATE TABLE tags (
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE
);

CREATE TABLE page_tags (
  page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  tag_id  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (page_id, tag_id)
);

CREATE TABLE api_tokens (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,                    -- 예: "claude-code 노트북"
  token_hash   TEXT NOT NULL UNIQUE,             -- SHA-256 (고엔트로피 토큰이므로 bcrypt 불필요)
  scope        TEXT NOT NULL CHECK (scope IN ('read','write')),
  created_at   TEXT NOT NULL,
  expires_at   TEXT,
  last_used_at TEXT,
  revoked_at   TEXT
);

CREATE TABLE link_previews (
  url_key    TEXT PRIMARY KEY,                -- 정규화 URL (#note_n, #L 범위 유지, 추적 파라미터 제거)
  provider   TEXT NOT NULL,                   -- gitlab:{name} | opengraph
  kind       TEXT NOT NULL CHECK (kind IN ('merge_request','issue','commit','project','wiki','blob','web')),
  status     TEXT NOT NULL CHECK (status IN ('ok','not_found','forbidden','error')),
  data       TEXT,                            -- JSON (카드 표시 항목)
  fetched_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE VIRTUAL TABLE pages_fts USING fts5(
  title, content, content='pages', content_rowid='id', tokenize='trigram'
);
-- pages INSERT/UPDATE/DELETE 트리거로 pages_fts 동기화
```

## 3. 불변 조건

| 조건 | 보장 방법 |
|------|----------|
| 활성 문서 경로 유일 | `ux_pages_path` 부분 인덱스 |
| `pages.path` = 조상 slug 결합 | 이동/이름 변경 서비스에서 하위 트리 일괄 갱신 (트랜잭션) |
| 첨부는 1개 페이지 소유 | `attachments.page_id NOT NULL` |
| 순환 부모 금지 | 이동 서비스에서 대상 부모가 자기 하위인지 검사 |
| `page_links`는 본문과 일치 | 저장 트랜잭션 안에서 재계산 |
