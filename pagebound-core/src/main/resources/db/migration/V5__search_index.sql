-- 검색 P1 (spec/features/F11-search.md 8절): 본문/코드/첨부명 분리 색인. pages_fts와 트리거 제거
-- 본문·코드 분리는 마크다운 해석이 필요해 애플리케이션이 색인한다. 트리거는 변경된 문서를 큐에 넣기만 한다
DROP TRIGGER IF EXISTS pages_fts_ai;
DROP TRIGGER IF EXISTS pages_fts_ad;
DROP TRIGGER IF EXISTS pages_fts_au;
DROP TABLE IF EXISTS pages_fts;

CREATE VIRTUAL TABLE search_index USING fts5(title, path, body, code, files, tokenize='trigram');

CREATE TABLE search_queue (page_id INTEGER PRIMARY KEY);

CREATE TRIGGER search_queue_page_ai AFTER INSERT ON pages BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (new.id);
END;
CREATE TRIGGER search_queue_page_au AFTER UPDATE OF title, path, content, is_draft, deleted_at ON pages BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (new.id);
END;
CREATE TRIGGER search_queue_page_ad AFTER DELETE ON pages BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (old.id);
END;
CREATE TRIGGER search_queue_file_ai AFTER INSERT ON attachments BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (new.page_id);
END;
CREATE TRIGGER search_queue_file_au AFTER UPDATE OF filename ON attachments BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (new.page_id);
END;
CREATE TRIGGER search_queue_file_ad AFTER DELETE ON attachments BEGIN
  INSERT OR IGNORE INTO search_queue (page_id) VALUES (old.page_id);
END;

INSERT INTO search_queue (page_id) SELECT id FROM pages;
