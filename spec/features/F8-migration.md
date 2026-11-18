# F8. Wiki.js 마이그레이션

관련: [00 현행 분석](../00-analysis.md), [02 데이터 모델](../02-data-model.md), 단계 [M1](../phases/M1-foundation.md), [M8](../phases/M8-cutover.md)

## 1. 실행 형태

- 독립 실행 CLI: `java -jar pagebound-migrator.jar --source database.sqlite --target data/`
- 원본은 `mode=ro`로 열기
- 재실행 가능: 대상 `pagebound.db`, `data/attachments/` 삭제 후 재생성 (`--force` 없으면 기존 대상 존재 시 중단)
- 결과 리포트 `data/migration-report.md` (경고/미해결 항목, 건수 요약)

## 2. 매핑

| 원본 | 대상 | 규칙 |
|------|------|------|
| `users` (isSystem=0) | `users` | password 그대로. Administrators→ADMIN, ems→EDITOR, 그 외→VIEWER. password NULL 제외 |
| `pageTree` 가상 폴더 (26) | `pages` | 제목=폴더명, 본문 빈 문자열 |
| `pages` (168) | `pages` | parent=경로 상위 노드, slug=마지막 경로 세그먼트, path=원 경로 그대로, title/createdAt/updatedAt/authorId/creatorId 계승. `nems/topology`는 문서 노드 하나로 통합 |
| `pages.path` | `page_aliases` | 레거시 URL 리다이렉트용 |
| `pageHistory` | `page_revisions` | pageId별 `versionDate` 오름차순 version 1..n, 현재 내용을 마지막 버전으로 추가. `source='migration'`. 삭제된 페이지 이력은 제외 후 리포트 |
| `assets` + `assetData` | `attachments` + 파일 | 3절 |
| `tags` + `pageTags` | `tags` + `page_tags` | [F4](F4-tags.md) 정규화, 콤마 포함 태그 분리 후 중복 제거, 미사용 제외 |
| 그 외 테이블 | - | 이전 안 함 |

## 3. 첨부 소유권 결정

| 참조 페이지 수 | 건수 | 처리 |
|---------------|------|------|
| 1 | 138 (M1 실측) | 해당 페이지 소유 |
| 2 이상 | 0 (M1 실측). 사전 분석의 3건은 파일명만 같은 오탐 | 참조 페이지마다 복제 |
| 0 | 17 (M1 실측) | 최상위 "미분류 첨부" 문서 소유 (Q3 확정) |

- 참조 판정: 본문 내 `/{folderPath}/{filename}` 및 URL 인코딩 변형 일치
- sha256 재계산, 원본 BLOB와 대조

## 4. 본문 변환

코드블록·인라인 코드 내부는 변환하지 않는다 (flexmark AST 기준).

1. 에셋 참조 → `/p/{pageId}/files/{filename}`
2. 내부 링크 `[텍스트](/경로)` (11건) → `[[경로|텍스트]]`. 대상 없는 8건은 레드 링크로 남음
3. DokuWiki 잔재 `[[http...|텍스트]]` → `[텍스트](http...)`. URL이 아닌 잔재는 원문 유지 + 리포트
4. `{.is-info}` 등 → `> [!INFO]` 콜아웃
5. `{.grid-list}`, `{.links-list}` 속성 제거
6. Notion S3 이미지 4건 (M1 실측: 전부 HTTP 403): 다운로드 시도, 성공 시 첨부 전환, 실패 시 원문 유지 + 리포트
7. `<details><summary>S</summary>` + 코드블록 1개 (26건) → `` ```{lang} title="S" collapsed ``. summary가 비었거나 `click to collapse` 같은 안내 문구면 title 생략 ([F9](F9-code-blocks.md))
8. `` ```diagram `` 블록 (22건 (M1 실측), 4문서, base64 draw.io SVG) → 디코드해 `diagram-{n}.svg` 첨부로 저장 + `![diagram-{n}](/p/{pageId}/files/diagram-{n}.svg)`로 치환. SVG 내 draw.io 원본 XML은 보존되어 draw.io에서 다시 열 수 있음
9. 그 외 본문 변경 없음
10. 변환 후 `page_links`, `pages_fts` 생성

본문 변환 결과는 현재 버전에만 적용한다. 과거 리비전은 원문 보존.

## 5. 정리 대상 (자동 처리 안 함, 리포트만)

- 임시 페이지 4건 (`*/new-page*`), 최상위 난립 항목 (Q6 확정: 이전 후 UI에서 정리)

## 6. 자동 검증

| 검사 | 기대값 |
|------|--------|
| 이전 문서 수 | 168 + 가상 폴더 26 + 미분류 첨부 1 = 195 |
| 원본 path → alias 조회 | 168/168 |
| 첨부 sha256 = 원본 BLOB sha256 | 전 건 |
| 본문 `/p/{id}/files/` 참조 → 첨부 존재 | 전 건 |
| 원본 pageTags 조합 보존 (정규화 후) | 전 건 |
| `<details>` 잔존 / `collapsed` 코드블록 | 0 / 26 |
| `` ```diagram `` 잔존 / diagram SVG 첨부 | 0 / 22 |
| 리비전 수 | 원본 이력 1,609(삭제 페이지 13건 제외) + 현재 버전 168 + 가상 폴더·미분류 문서 각 1 = 1,804 |
| 기존 비밀번호 로그인 | 성공 (M2에서 확인) |
| 195문서 렌더 스모크 | JS 오류 0 (M2에서 확인) |
| 섹션 분할 서버(flexmark) = 클라이언트(markdown-it) | 195/195 (M2에서 확인) |
