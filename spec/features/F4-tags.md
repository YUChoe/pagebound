# F4. 태그 (R4, 화면 명칭: 분류)

관련: [F5 분류 바](F5-page-view.md), [F7 검색](F7-common.md), 단계 [M2](../phases/M2-read.md), [M4](../phases/M4-organize.md)

## 1. 규칙

- 정규화: 앞뒤 공백 제거, 연속 공백 1개, 대소문자 무시 중복 판정(`COLLATE NOCASE`), 최대 40자
- 콤마는 구분자 (태그명에 포함 불가)
- 문서당 최대 20개

## 2. 기능

| 화면 | 기능 |
|------|------|
| 에디터 | 칩 입력, 자동완성(기존 태그 + 사용 수), Enter/콤마 확정, Backspace 삭제 |
| 문서 | 제목 아래 namu식 분류 바 `분류: a \| b \| c`, 클릭 시 태그 목록 |
| `/tags` | 전체 태그 + 사용 수, 이름/사용수 정렬 |
| `/tags/{a}+{b}` | 다중 태그 AND 필터 목록 (제목, 경로, 수정일) |
| 검색 | 검색어 + 태그 필터 |
| 관리자 | 이름 변경, 병합(A→B), 미사용 삭제 |

## 3. API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/tags?q=` | 목록/자동완성 (사용 수 포함) |
| GET | `/api/v1/tags/pages?names=a,b` | AND 필터 |
| PUT | `/api/v1/pages/{id}/tags` | 문서 태그 전체 교체 |
| PATCH | `/api/v1/tags/{id}` | 이름 변경 (관리자) |
| POST | `/api/v1/tags/{id}/merge` | `targetId`로 병합 (관리자) |
| DELETE | `/api/v1/tags/{id}` | 미사용 태그 삭제 (관리자) |

## 4. 완료 기준

| 항목 | 기준 |
|------|------|
| 중복 방지 | `Java`, `java ` 입력 시 기존 `java`로 연결 |
| AND 필터 | `nems+altibase` 결과 = 두 태그 모두 가진 문서 |
| 병합 | A→B 병합 후 A 사용 문서 전부 B 보유, A 삭제 |
