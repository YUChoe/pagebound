-- Pagebound 초기 스키마 (spec/02-data-model.md)
-- 연결 URL에 foreign_keys=true, journal_mode=WAL 필수

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
  slug        TEXT NOT NULL,
  path        TEXT NOT NULL,
  title       TEXT NOT NULL,
  content     TEXT NOT NULL DEFAULT '',
  sort_order  INTEGER NOT NULL DEFAULT 0,
  version     INTEGER NOT NULL DEFAULT 1,
  is_draft    INTEGER NOT NULL DEFAULT 0,
  created_by  INTEGER REFERENCES users(id),
  updated_by  INTEGER REFERENCES users(id),
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  deleted_at  TEXT
);
CREATE UNIQUE INDEX ux_pages_path ON pages (path) WHERE deleted_at IS NULL;
CREATE INDEX ix_pages_parent ON pages (parent_id, sort_order);
CREATE INDEX ix_pages_updated ON pages (updated_at DESC);

CREATE TABLE api_tokens (
  id           INTEGER PRIMARY KEY,
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  token_hash   TEXT NOT NULL UNIQUE,
  scope        TEXT NOT NULL CHECK (scope IN ('read','write')),
  created_at   TEXT NOT NULL,
  expires_at   TEXT,
  last_used_at TEXT,
  revoked_at   TEXT
);

CREATE TABLE page_revisions (
  id         INTEGER PRIMARY KEY,
  page_id    INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  version    INTEGER NOT NULL,
  title      TEXT NOT NULL,
  path       TEXT NOT NULL,
  content    TEXT NOT NULL,
  action     TEXT NOT NULL CHECK (action IN ('created','updated','moved','restored','link-updated')),
  source     TEXT NOT NULL CHECK (source IN ('web','mcp','api','system','migration')),
  summary    TEXT,
  author_id  INTEGER REFERENCES users(id),
  token_id   INTEGER REFERENCES api_tokens(id),
  created_at TEXT NOT NULL,
  UNIQUE (page_id, version)
);

CREATE TABLE page_aliases (
  path    TEXT PRIMARY KEY,
  page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE
);

CREATE TABLE page_links (
  source_page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  target_path    TEXT NOT NULL,
  target_page_id INTEGER REFERENCES pages(id) ON DELETE SET NULL,
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

CREATE TABLE link_previews (
  url_key    TEXT PRIMARY KEY,
  provider   TEXT NOT NULL,
  kind       TEXT NOT NULL CHECK (kind IN ('merge_request','issue','commit','project','wiki','blob','web')),
  status     TEXT NOT NULL CHECK (status IN ('ok','not_found','forbidden','error')),
  data       TEXT,
  fetched_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

-- 전문 검색 (external content). pages 변경 시 트리거로 동기화
CREATE VIRTUAL TABLE pages_fts USING fts5(
  title, content, content='pages', content_rowid='id', tokenize='trigram'
);

CREATE TRIGGER pages_fts_ai AFTER INSERT ON pages BEGIN
  INSERT INTO pages_fts (rowid, title, content) VALUES (new.id, new.title, new.content);
END;

CREATE TRIGGER pages_fts_ad AFTER DELETE ON pages BEGIN
  INSERT INTO pages_fts (pages_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
END;

CREATE TRIGGER pages_fts_au AFTER UPDATE OF title, content ON pages BEGIN
  INSERT INTO pages_fts (pages_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
  INSERT INTO pages_fts (rowid, title, content) VALUES (new.id, new.title, new.content);
END;
