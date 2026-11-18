# Pagebound 스펙 인덱스

- 작성일: 2026-11-18 (rev.3: 기능별/단계별 분리)
- 프로젝트명: Pagebound (코드/산출물 식별자 `pagebound`). 첨부와 하위 문서가 페이지에 종속(bound)되는 위키
- 입력: `database.sqlite` (Wiki.js 2.x export), 이전 서비스 `http://<legacy-wiki>/`
- 뷰 레퍼런스: `https://namu.wiki`
- 상태: M0 완료(2026-11-18, 결정 사항 Q1~Q12 확정). M1 착수 가능

## 문서 구성

```
spec/
├── README.md              인덱스, 목표, 추적표, 결정 사항
├── 00-analysis.md         현행 데이터/화면 분석
├── 01-architecture.md     핵심 결정, 서버 언어, 기술 스택, 모듈 구성, 비기능
├── 02-data-model.md       DB 스키마
├── features/
│   ├── F1-editor.md       쓰기·붙여넣기
│   ├── F2-attachments.md  페이지 종속 첨부
│   ├── F3-tree-links.md   디렉토리 구조, URL, 위키 링크
│   ├── F4-tags.md         태그(분류)
│   ├── F5-page-view.md    문서 화면 (namu.wiki 구성)
│   ├── F6-mcp-api.md      MCP 서버, API 토큰, REST API
│   ├── F7-common.md       검색, 역사, 권한, 사용자
│   ├── F8-migration.md    Wiki.js 마이그레이션
│   ├── F9-code-blocks.md  코드 보기, 코드 접기(상태 유지)
│   └── F10-link-preview.md GitLab 이슈/MR 등 웹 링크 미리보기
└── phases/
    ├── M1-foundation.md   골격, 스키마, 마이그레이터
    ├── M2-read.md         읽기 화면
    ├── M3-write.md        에디터, 첨부
    ├── M4-organize.md     트리 편집, 태그, 검색
    ├── M5-mcp.md          MCP, 토큰, REST
    ├── M6-link-preview.md 웹 링크 미리보기
    ├── M7-history-p1.md   역사, P1 기능, 관리자
    └── M8-cutover.md      리허설, 전환
```

## 목표

| # | 요구사항 | 성공 기준 |
|---|---------|-----------|
| R1 | 쓰기 개선 (이미지·파일 붙여넣기) | 스크린샷 Ctrl+V 한 번으로 업로드 + 본문 삽입 완료 |
| R2 | 에셋을 페이지에 종속 | 모든 첨부는 정확히 1개 페이지 소유. 페이지 삭제 시 첨부도 삭제. 전역 에셋 브라우저 없음 |
| R3 | 디렉토리 구조 개선 | 전체 트리 탐색, 이동 시 링크 자동 갱신, 빈 폴더/경로 충돌 없음 |
| R4 | 태그 기능 | 자동완성 입력, 분류 바 표시, 다중 태그 필터 |
| R5 | 페이지 뷰 개선 | namu.wiki 문서 화면 구성 적용 |
| R6 | 에이전트 연동 | MCP 서버로 검색·조회·작성·첨부, 사용자 단위 토큰과 이력 추적 |
| R7 | 서버 언어 선정 | Java 21 + Spring Boot ([01-architecture.md](01-architecture.md) D5) |
| R8 | 개발자용 코드 보기/접기 | 코드블록 접기 상태가 새로고침 후 유지, 문서 기본 접힘 지정 가능 |
| R9 | 웹 링크 미리보기 | 사내 GitLab 이슈/MR 링크에 제목·상태 표시, 단독 줄 URL은 카드 |

## 추적표 (요구사항 → 기능 → 단계)

| 요구사항 | 기능 스펙 | 단계 |
|---------|----------|------|
| R1 | [F1](features/F1-editor.md) | M3 (P0), M7 (P1) |
| R2 | [F2](features/F2-attachments.md), [F8](features/F8-migration.md) | M1 (이전), M3 |
| R3 | [F3](features/F3-tree-links.md) | M2 (조회, 링크 해석), M4 (이동, DnD, 휴지통) |
| R4 | [F4](features/F4-tags.md) | M2 (분류 바 표시), M4 (편집, 필터, 관리) |
| R5 | [F5](features/F5-page-view.md) | M2 (P0), M7 (P1) |
| R6 | [F6](features/F6-mcp-api.md) | M5 |
| R7 | [01-architecture.md](01-architecture.md) | M1 |
| R8 | [F9](features/F9-code-blocks.md), [F8](features/F8-migration.md) | M1 (`<details>` 이전), M2 (P0), M7 (P1) |
| R9 | [F10](features/F10-link-preview.md) | M6 |
| 공통 | [F7](features/F7-common.md) | M2 (로그인, 권한), M4 (검색), M7 (역사, 관리자) |

## 단계 요약

| 단계 | 문서 | 선행 | 예상 |
|------|------|------|------|
| M0 | 아래 결정 사항 확정 (완료) | - | 30분 |
| M1 | [골격, 스키마, 마이그레이터](phases/M1-foundation.md) | M0 | 2일 |
| M2 | [읽기 화면](phases/M2-read.md) | M1 | 3일 |
| M3 | [에디터, 첨부](phases/M3-write.md) | M2 | 3일 |
| M4 | [트리 편집, 태그, 검색](phases/M4-organize.md) | M3 | 3일 |
| M5 | [MCP, 토큰, REST](phases/M5-mcp.md) | M3 | 2일 |
| M6 | [웹 링크 미리보기](phases/M6-link-preview.md) | M2 | 3일 |
| M7 | [역사, P1, 관리자](phases/M7-history-p1.md) | M4 | 2일 |
| M8 | [리허설, 전환](phases/M8-cutover.md) | M5, M6, M7 | 1일 |

## 결정 사항 (M0)

| # | 질문 | 결정 | 영향 문서 |
|---|------|--------|----------|
| Q1 | 서버 언어 Java 21 + Spring Boot 확정 여부 | 확정(2026-11-18): Java 21 + Spring Boot | 01-architecture |
| Q2 | MCP 접근 범위: 사내망 한정인지 | 확정(2026-11-18): 사내망 한정, Bearer 토큰. OAuth 미도입 | F6 |
| Q3 | 고아 에셋 12건: 삭제 / "미분류 첨부" 문서에 보관 | 확정(2026-11-18): "미분류 첨부" 문서 보관 | F8 |
| Q4 | 미저장 신규 문서 붙여넣기 시 초안 선생성 허용 | 확정(2026-11-18): 허용 (초안은 트리 미표시) | F1 |
| Q5 | 에이전트의 `move_page`, `trash_page` 허용 여부 | 확정(2026-11-18): 허용 (영구 삭제 불가) | F6 |
| Q6 | 임시 페이지 4건, 최상위 26개 난립 항목 재배치 시점 | 확정(2026-11-18): 그대로 이전 후 UI에서 정리 | F8 |
| Q7 | 익명 읽기 유지 | 확정(2026-11-18): 유지 | F7 |
| Q8 | 배포: 기존 `<legacy-wiki>` 대체 여부, Docker/jar | 확정(2026-11-18): 기존 `<legacy-wiki>` 대체, 레거시 URL 리다이렉트. 배포 형태 jar + systemd | M8 |
| Q9 | Wiki.js 병행 운영 필요 여부 | 확정(2026-11-18): 병행 운영 없음, 완전 이전 (일괄 전환) | F8, M8 |
| Q10 | 코드 접기 상태를 브라우저(localStorage)에만 저장해도 되는지, 기기 간 동기화가 필요한지 | 확정(2026-11-18): localStorage + 문서 기본값(`collapsed`). 동기화는 P2 | F9 |
| Q11 | 링크 미리보기 GitLab 인증: 서비스 계정 토큰(공용) / 사용자별 GitLab 토큰 | 확정(2026-11-18): 서비스 계정 공용 토큰 `read_api` + 위키 로그인 사용자에게만 표시 | F10 |
| Q12 | 위키 서버의 외부 인터넷 접근 가능 여부 (일반 웹 OG 미리보기) | 확정(2026-11-18): 접근 가능 → OpenGraph 프로바이더 사용 | F10 |

## 백로그 (P2)

- 랜덤 문서 버튼, 즐겨찾기(☆) — [F5](features/F5-page-view.md)
- 코드 접기 상태 기기 간 동기화(서버 저장) — [F9](features/F9-code-blocks.md)
- GitLab `#note_n` 댓글 내용 미리보기, 본문 내 `group/project!13` 단축 참조 자동 링크 — [F10](features/F10-link-preview.md)
