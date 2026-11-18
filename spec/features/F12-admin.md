# F12. 관리자 기능 (R11)

관련: [F7 권한·사용자](F7-common.md), [F3 휴지통](F3-tree-links.md) 6절, [F4 태그](F4-tags.md), [F6 토큰](F6-mcp-api.md) 2절, [F10 링크 미리보기](F10-link-preview.md), [F11 검색](F11-search.md) 8절, [02 데이터 모델](../02-data-model.md), 단계 [M4](../phases/M4-organize.md), [M5](../phases/M5-mcp.md), [M7](../phases/M7-history-p1.md)

## 1. 근거 (M1 이전 데이터 실측)

| 항목 | 값 | 시사점 |
|------|----|--------|
| 사용자 | 12명: ADMIN 1, EDITOR 11(비활성 2), VIEWER 0 | 관리자가 1명뿐 → 마지막 관리자 보호 필수, 2명 이상 지정 권장 |
| 저장소 | 첨부 177개 77.5MB, DB 52.7MB | 용량 현황 표시 |
| 이전 후 정리 대상 | 미분류 첨부 17, 임시 문서 4, 레드 링크 23 | 정리 도구 |
| 에이전트 쓰기 | MCP 토큰으로 문서 수정 가능 (F6) | 토큰·호출 감사 |

## 2. 접근

- 화면 `/admin/**`, API `/api/v1/admin/**`: ADMIN만 (그 외 403, 익명 401)
- 헤더에 ADMIN에게만 "관리" 메뉴 표시
- 모든 관리 작업은 감사 로그(12절)에 기록

## 3. 화면 구성

| 화면 | 경로 | 내용 | 우선순위 |
|------|------|------|---------|
| 대시보드 | `/admin` | 현황 요약 (4절) | P0 |
| 사용자 | `/admin/users` | 5절 | P0 |
| 휴지통 | `/admin/trash` | 6절 | P0 |
| 태그 | `/admin/tags` | 8절 | P0 |
| API 토큰·에이전트 | `/admin/tokens` | 9절 | P0 (호출 로그 P1) |
| 감사 로그 | `/admin/audit` | 12절 | P0 |
| 정리 도구 | `/admin/cleanup` | 7절 | P1 |
| 설정 | `/admin/settings` | 10절 | P1 |
| 유지보수 | `/admin/maintenance` | 11절 | P1 |

## 4. 대시보드

| 항목 | 우선순위 |
|------|---------|
| 문서 수 (활성 / 휴지통 / 초안), 첨부 수·용량, DB 파일 크기 | P0 |
| 사용자 수 (역할별, 비활성), 활성 ADMIN 1명이면 경고 배너 | P0 |
| 최근 7일 편집 수 (웹 / 에이전트 / 시스템 구분) | P0 |
| 정리 대상 건수: 미참조 첨부, 레드 링크, 7일 내 영구 삭제 예정 문서 | P0 |
| 최근 감사 로그 5건, 서버 버전·기동 시각 | P0 |
| 마지막 백업 시각, 데이터 디렉토리 디스크 여유 공간 | P1 |

## 5. 사용자 관리

| 기능 | 사양 | 우선순위 |
|------|------|---------|
| 목록 | 이름, 이메일, 역할, 상태, 마지막 로그인, 토큰 수, 편집 수. 이름·이메일 검색, 역할·상태 필터 | P0 |
| 생성 | 이메일, 이름, 역할, 초기 비밀번호(자동 생성 16자를 1회만 표시하거나 직접 입력, 최소 8자) | P0 |
| 역할 변경 | ADMIN / EDITOR / VIEWER | P0 |
| 비활성화·활성화 | 비활성화 즉시 해당 사용자의 세션 만료(세션 레지스트리)와 API 토큰 무효 | P0 |
| 비밀번호 초기화 | 임시 비밀번호 1회 표시 | P0 |
| 다음 로그인 시 비밀번호 변경 강제 | 생성·초기화 시 기본 체크 (`users.must_change_password`) | P1 |
| 사용자별 활동 | 최근 편집 문서, 보유 토큰 | P1 |
| 로그인 이력, 비밀번호 정책 설정 | | P2 |

제약:
1. 마지막 활성 ADMIN은 강등·비활성화할 수 없다 (409 `LAST_ADMIN`)
2. 자기 자신은 강등·비활성화할 수 없다
3. 사용자 삭제는 제공하지 않는다. 리비전·첨부 작성자 기록(FK)을 보존하기 위해 비활성화로 대체
4. 이메일은 대소문자 무시로 중복 금지

## 6. 휴지통 ([F3](F3-tree-links.md) 6절의 관리 화면)

| 기능 | 사양 | 우선순위 |
|------|------|---------|
| 목록 | 제목, 원래 경로, 삭제자, 삭제 시각, 영구 삭제 예정일, 하위 문서 수, 첨부 용량 | P0 |
| 복원 | 원래 경로가 점유되어 있으면 새 slug 입력 | P0 |
| 즉시 영구 삭제 | 확인을 위해 문서 제목을 직접 입력. 첨부 파일 포함 삭제 | P0 |
| 보관 기간 변경 | 설정 화면 `trash.retention-days` | P1 |

삭제자 표시를 위해 `pages.deleted_by`를 추가한다 (13절, M4 휴지통 구현과 함께).

## 7. 정리 도구

| 기능 | 사양 | 우선순위 |
|------|------|---------|
| 미참조 첨부 | 본문에서 참조하지 않는 첨부를 문서별·용량순으로 나열, 선택 일괄 삭제 | P1 |
| 첨부 이동 | "미분류 첨부" 문서 등의 첨부를 다른 문서로 이동 (복사 후 원본 삭제) | P1 |
| 레드 링크 | 전체 레드 링크를 대상 경로별 참조 수로 나열, "문서 만들기" | P1 |
| 빈 문서 | 본문도 하위 문서도 없는 문서 목록 | P1 |
| 초안 | 정리 전 초안 목록과 수동 삭제 | P1 |

## 8. 태그 ([F4](F4-tags.md) 2절의 관리자 항목)

- 전체 목록(사용 수), 이름 변경, 병합(A→B), 미사용 삭제 (P0)

## 9. API 토큰·에이전트

| 기능 | 사양 | 우선순위 |
|------|------|---------|
| 전체 토큰 | 사용자, 이름, scope, 만료, 마지막 사용 시각 | P0 |
| 강제 폐기 | 즉시 무효 | P0 |
| MCP 호출 로그 | 토큰·도구·대상 경로·결과 코드·시각, 필터. 토큰별 일간 호출 수. 90일 보관 (`mcp_call_log`) | P1 |

## 10. 설정 (런타임 변경)

DB `settings`에 저장하고 즉시 반영한다. 값이 없으면 `application.yml` 기본값을 쓴다.

| 키 | 기본값 | 설명 |
|----|--------|------|
| `site.name` | Pagebound | 헤더·브라우저 제목 |
| `site.home-path` | `home` | `/` 접속 시 이동할 문서 |
| `security.anonymous-read` | `true` | 익명 읽기 (Q7) |
| `upload.max-file-size` | 50MB | 서버 multipart 한도(52MB) 이내로만 설정 가능 |
| `trash.retention-days` | 30 | 휴지통 보관 기간 |
| `draft.retention-hours` | 24 | 붙여넣기용 초안 보관 시간 |
| `mcp.rate-limit-per-minute` | 60 | 토큰당 MCP 호출 한도 |

- GitLab 토큰은 설정 화면에서 다루지 않는다 (F10: 외부 설정으로만 주입). 대신 GitLab 연결 상태(버전 조회 성공 여부)만 표시
- 우선순위: P1

## 11. 유지보수

| 작업 | 사양 | 우선순위 |
|------|------|---------|
| 검색 색인 재구축 | [F11](F11-search.md) 8절 | P1 |
| 링크 인덱스 재계산 | 전체 `page_links` 재생성 | P1 |
| 링크 미리보기 캐시 비우기 | [F10](F10-link-preview.md) | P1 |
| 초안 정리 즉시 실행 | [F1](F1-editor.md) 2절 규칙 5 | P1 |
| 마이그레이션 리포트 보기 | `data/migration-report.md`가 있을 때 | P1 |
| 백업 즉시 실행·다운로드 | M8 백업 방식과 연동 | P2 |

각 작업은 실행 결과(처리 건수, 소요 시간)를 표시하고 감사 로그에 남긴다.

## 12. 감사 로그

- 기록 대상: 사용자 생성·역할 변경·비활성화·활성화·비밀번호 초기화, 문서 휴지통 이동·복원·영구 삭제, 태그 이름 변경·병합·삭제, 토큰 강제 폐기, 설정 변경, 유지보수 실행, 로그인 실패 5회 연속
- 기록하지 않음: 일반 문서 편집 (`page_revisions`가 이력)
- 화면: 기간·행위자·작업 종류 필터, 50건 단위
- 보관: 1년
- 우선순위: P0

## 13. 데이터 모델 추가

```sql
CREATE TABLE audit_log (
  id          INTEGER PRIMARY KEY,
  actor_id    INTEGER REFERENCES users(id),   -- 시스템 작업이면 NULL
  action      TEXT NOT NULL,                  -- USER_CREATE, USER_ROLE, PAGE_PURGE, SETTING_UPDATE …
  target_type TEXT,                           -- user, page, tag, token, setting, maintenance
  target_id   TEXT,
  detail      TEXT,                           -- JSON (변경 전후 값 등, 비밀번호·토큰 값 제외)
  created_at  TEXT NOT NULL
);
CREATE INDEX ix_audit_created ON audit_log (created_at DESC);

ALTER TABLE pages ADD COLUMN deleted_by INTEGER REFERENCES users(id);         -- P0 (M4)

CREATE TABLE settings (                                                       -- P1
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_by INTEGER REFERENCES users(id),
  updated_at TEXT NOT NULL
);

ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0;  -- P1

CREATE TABLE mcp_call_log (                                                   -- P1
  id         INTEGER PRIMARY KEY,
  token_id   INTEGER REFERENCES api_tokens(id) ON DELETE SET NULL,
  tool       TEXT NOT NULL,
  target     TEXT,
  result     TEXT NOT NULL,                   -- OK 또는 오류 코드
  created_at TEXT NOT NULL
);
CREATE INDEX ix_mcp_call_created ON mcp_call_log (created_at DESC);
```

- Flyway 마이그레이션 번호는 구현 순서에 따라 정한다 (F11 `search_index`와 함께 M7)

## 14. API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/admin/dashboard` | 4절 |
| GET / POST | `/api/v1/admin/users` | 목록 / 생성 (응답에 임시 비밀번호 1회 포함) |
| PATCH | `/api/v1/admin/users/{id}` | 역할, 활성 상태 |
| POST | `/api/v1/admin/users/{id}/reset-password` | 임시 비밀번호 발급 |
| GET | `/api/v1/admin/trash` | 휴지통 목록 |
| POST / DELETE | `/api/v1/admin/trash/{id}/restore`, `/api/v1/admin/trash/{id}` | 복원 / 영구 삭제 |
| GET / DELETE | `/api/v1/admin/tokens`, `/api/v1/admin/tokens/{id}` | 전체 토큰 / 강제 폐기 |
| GET | `/api/v1/admin/audit?from=&to=&actor=&action=&page=` | 감사 로그 |
| GET / PUT | `/api/v1/admin/settings` | 설정 (P1) |
| POST | `/api/v1/admin/maintenance/{task}` | 유지보수 실행 (P1) |
| GET | `/api/v1/admin/cleanup/{kind}` | 정리 대상 목록 (P1) |

태그 관리 API는 [F4](F4-tags.md) 3절, 휴지통 복원 API는 이 절로 일원화한다 (F3 7절의 `/api/v1/trash`는 `/api/v1/admin/trash`로 변경).

## 15. 완료 기준

| 항목 | 기준 |
|------|------|
| 권한 | EDITOR·VIEWER의 `/api/v1/admin/**` 호출 403, 익명 401 |
| 마지막 관리자 | 유일한 활성 ADMIN을 강등·비활성화하면 409 `LAST_ADMIN` |
| 비활성화 | 비활성화된 사용자의 기존 세션 다음 요청에서 401, 토큰 401 |
| 사용자 생성 | 임시 비밀번호로 로그인 성공 |
| 휴지통 | 복원 시 원래 경로로 돌아오고 링크 연결 복구, 영구 삭제 시 첨부 디렉토리 삭제 |
| 감사 | 위 작업마다 감사 로그 1건, 비밀번호·토큰 값이 detail에 없음 |
| 대시보드 | 문서·첨부·사용자 수가 DB 실제 값과 일치 |
