# M7. 역사, P1 기능, 관리자

- 선행: M4
- 예상: 4일 (F12 관리자 기능 확장으로 +2일)
- 범위:
  - [F7](../features/F7-common.md) 2절(역사)
  - [F12](../features/F12-admin.md) P0 (대시보드, 사용자, 휴지통, 태그, 토큰, 감사 로그) + P1 (정리 도구, 설정, 유지보수, MCP 호출 로그)
  - [F1](../features/F1-editor.md) 2절 P1 (TSV, HTML, URL 붙여넣기)
  - [F5](../features/F5-page-view.md) P1 (플로팅 버튼, 각주 툴팁, 역링크, 다크 테마, 인쇄)
  - [F11](../features/F11-search.md) P1 (`in:title`·`in:code`·`in:file`, 첨부 파일명 검색, `search_index` V2 색인, 섹션 바로가기, 문서 내 강조, 색인 재구축)
  - [F9](../features/F9-code-blocks.md) P1 (줄 강조, start=, 줄 앵커, 전체화면, 원문/다운로드, 기본 접힘 저장, until-found, 원문 보기, 에디터 코드블록 대화상자)

## 작업

| # | 작업 | 검증 |
|---|------|------|
| 1 | 역사 화면: 버전 목록(source 배지, ±bytes), side-by-side diff, 복원 | 복원 후 본문 일치 |
| 2 | 붙여넣기 P1: 엑셀 TSV → 표, HTML → turndown(이미지 첨부화), 선택 텍스트 + URL → 링크 | 엑셀 3x3 붙여넣기 → 표 |
| 3 | 문서 화면 P1: 플로팅 버튼, 각주 툴팁, `⋮` 역링크 | 시각 확인 |
| 3a | 검색 P1: Flyway V2 `search_index`(본문/코드/첨부명 분리) + 범위 지정 + 섹션 바로가기 + `?hl=` 강조·자동 펼침 | `in:code` 결과가 코드 일치 문서만 |
| 4 | 다크 테마, 인쇄 스타일 (접힌 코드 펼쳐 출력) | 시각 확인 |
| 4a | 코드블록 P1 기능 + `PATCH /code-blocks/{index}` + `/source/{path}` 원문 보기 | 기본 접힘 저장 후 다른 사용자 첫 진입 시 접힘 |
| 5 | 관리자 P0: 대시보드, 사용자(생성·역할·비활성화·비밀번호 초기화, 마지막 관리자 보호, 세션 즉시 만료), 휴지통, 태그, 토큰, 감사 로그 | [F12](../features/F12-admin.md) 15절 |
| 6 | 관리자 P1: `settings`·`must_change_password`·`mcp_call_log` 마이그레이션, 설정 화면, 정리 도구, 유지보수 작업 | 설정 변경 즉시 반영, 작업 결과 표시 |

## 완료 기준

1. [F7](../features/F7-common.md) 6절 전 항목 통과
2. 웹 페이지 HTML 붙여넣기 시 포함 이미지가 현재 문서 첨부로 저장
