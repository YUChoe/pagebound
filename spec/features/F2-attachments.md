# F2. 페이지 종속 첨부 (R2)

관련: [F1 붙여넣기](F1-editor.md), [F8 마이그레이션](F8-migration.md), 단계 [M1](../phases/M1-foundation.md), [M3](../phases/M3-write.md)

## 1. 원칙

- 첨부는 반드시 1개 페이지 소유 (`attachments.page_id NOT NULL`), 전역 에셋 브라우저/에셋 폴더 없음
- 페이지 영구 삭제 시 첨부 DB 레코드 + 파일 삭제 (휴지통 단계에서는 유지)
- 첨부 URL은 page_id 기반이므로 문서 이동/이름 변경에 영향 없음
- 저장 위치: `data/attachments/{page_id}/{attachment_id}`

## 2. URL과 응답

- 본문 저장 형식: `/p/{pageId}/files/{filename}` (filename URL 인코딩)
- 이미지/PDF inline, 그 외 `Content-Disposition: attachment`
- SVG는 `Content-Security-Policy: sandbox` 헤더로 서빙
- `ETag`(sha256), Range 요청 지원
- 권한: 소유 페이지 읽기 권한 = 첨부 읽기 권한

## 3. 첨부 패널

- 위치: 에디터 사이드 패널 + 문서 하단 "첨부 파일" 섹션
- 항목: 썸네일(이미지), 파일명, 크기, 업로더, 업로드일
- 동작: 업로드, 다운로드, 이름 변경(본문 참조 자동 치환), 삭제, 본문에 삽입
- "본문 미참조" 배지: 본문에 URL이 없는 첨부 표시 + 일괄 정리

## 4. 저장 시 검증

- 본문이 다른 페이지 첨부(`/p/{다른id}/files/...`) 참조 시 경고 + "이 페이지로 복사" 액션
- 존재하지 않는 첨부 참조 시 경고 (저장은 허용)

## 5. API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/p/{pageId}/files/{filename}` | 다운로드/표시 |
| GET | `/api/v1/pages/{id}/attachments` | 목록 (본문 참조 여부 포함) |
| POST | `/api/v1/pages/{id}/attachments` | multipart 업로드, 최종 파일명/URL 응답 |
| PATCH | `/api/v1/pages/{id}/attachments/{attId}` | 이름 변경 (`rewriteContent=true` 시 본문 치환 + 리비전) |
| DELETE | `/api/v1/pages/{id}/attachments/{attId}` | 삭제 |
| POST | `/api/v1/pages/{id}/attachments/copy` | 다른 페이지 첨부를 이 페이지로 복사 |

## 6. 완료 기준

| 항목 | 기준 |
|------|------|
| 종속성 | 페이지 영구 삭제 후 해당 디렉토리와 레코드 0건 |
| 이동 무관 | 문서 이동 후 첨부 이미지 정상 표시 |
| 이름 변경 | 본문 참조 URL 자동 치환 |
| 미참조 표시 | 본문에서 이미지 줄 삭제 후 저장 시 배지 표시 |
