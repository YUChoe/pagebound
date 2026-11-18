-- 관리자 P1 (spec/features/F12-admin.md 13절): 런타임 설정, 비밀번호 변경 강제, MCP 호출 로그
CREATE TABLE settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_by INTEGER REFERENCES users(id),
  updated_at TEXT NOT NULL
);

ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0;

CREATE TABLE mcp_call_log (
  id         INTEGER PRIMARY KEY,
  token_id   INTEGER REFERENCES api_tokens(id) ON DELETE SET NULL,
  tool       TEXT NOT NULL,
  target     TEXT,
  result     TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX ix_mcp_call_created ON mcp_call_log (created_at DESC);

-- blame 규칙 변경(시스템 변경 무시, F13 P1): 캐시 재계산
DELETE FROM page_blame;
