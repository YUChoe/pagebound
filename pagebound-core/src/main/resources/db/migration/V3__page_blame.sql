-- 행별 blame 캐시 (spec/features/F13-blame.md 5절). 지워도 다시 계산된다.
CREATE TABLE page_blame (
  page_id INTEGER PRIMARY KEY REFERENCES pages(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  lines   TEXT NOT NULL
);
