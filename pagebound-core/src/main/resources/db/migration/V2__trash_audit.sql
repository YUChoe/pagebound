-- M4: 휴지통 삭제자 기록, 관리 작업 감사 로그 (spec/features/F12-admin.md 13절)

ALTER TABLE pages ADD COLUMN deleted_by INTEGER REFERENCES users(id);

CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY,
  actor_id    INTEGER REFERENCES users(id),
  action      TEXT NOT NULL,
  target_type TEXT,
  target_id   TEXT,
  detail      TEXT,
  created_at  TEXT NOT NULL
);
CREATE INDEX ix_audit_created ON audit_log (created_at DESC);
