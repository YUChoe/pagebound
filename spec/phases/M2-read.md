# M2. 읽기 화면

- 선행: M1
- 예상: 3일
- 범위:
  - [F3](../features/F3-tree-links.md) 2절(URL), 3절(링크 해석), 5절(트리 조회), 7절 중 조회 API
  - [F4](../features/F4-tags.md) 분류 바 표시
  - [F5](../features/F5-page-view.md) P0 전체
  - [F7](../features/F7-common.md) 3~4절 (로그인, 권한, 익명 읽기)
  - [F9](../features/F9-code-blocks.md) P0 (info string, 헤더 바, 줄 번호, 복사, 줄바꿈, 긴 코드, 자동 감지, 접기 + 상태 유지, 모두 접기/펼치기)

## 작업

| # | 작업 | 검증 |
|---|------|------|
| 1 | Spring Security: 폼 로그인, role 권한, 익명 읽기 설정 | 마이그레이션 사용자 기존 비밀번호 로그인 |
| 2 | 조회 API: 트리, `by-path`(링크 해석·분류·첨부 요약 포함), 최근 변경, 첨부 다운로드, `/w/{path}.md` | API 테스트 |
| 3 | 레거시/alias 리다이렉트 (`/{path}`, `/ko/{path}` → `/w/{path}` 301), `/p/{id}` 302 | 원본 168경로 전부 301 |
| 4 | Vue 앱 골격: 레이아웃(헤더, 트리, 문서 카드, 우측 최근 변경), 라우팅, 반응형 구간 | 1440/1024/390px 확인 |
| 5 | markdown-it 렌더러: 위키 링크/레드 링크 플러그인, 헤딩 번호, 섹션 접기, 인라인 목차, 콜아웃, details, mermaid(지연), highlight.js, DOMPurify | 194문서 렌더 스모크 JS 오류 0 |
| 6 | 본문 스타일 ([F5](../features/F5-page-view.md) 3절 P0) | 시각 확인 |
| 6a | 코드블록 컴포넌트 ([F9](../features/F9-code-blocks.md) P0), 자동 감지 임계값 실데이터 조정 | [F9](../features/F9-code-blocks.md) 8절 중 P0 항목 |
| 7 | 섹션 분할 비교 테스트: 서버(flexmark) vs 클라이언트(markdown-it) | 194/194 일치 |
| 8 | 성능 측정: 최대 문서 렌더, 트리 API | 1초 / 200ms 이내 |

## 완료 기준

1. 로그인/익명 상태 모두 194문서 열람 가능
2. `home`의 대상 없는 링크 6건이 레드 링크로 표시 (M2 실측. 사전 분석의 8건 중 2건은 DokuWiki URL이라 일반 링크로 변환됨)
3. 위 작업 표의 검증 전부 통과

## 미포함

- 편집, 섹션 편집 버튼 동작 (M3), 트리 DnD (M4)
