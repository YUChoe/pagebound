# M6. 웹 링크 미리보기

- 선행: M2 (M3~M5와 병행 가능)
- 예상: 3일
- 범위: [F10](../features/F10-link-preview.md) 전체, [02 link_previews](../02-data-model.md)

## 작업

| # | 작업 | 검증 |
|---|------|------|
| 1 | 서비스 계정 토큰 발급 요청 (두 GitLab, `read_api`, 대상 그룹 Reporter), 설정 외부화 | `GET /version` 성공 |
| 2 | URL 파서: MR/이슈/커밋/프로젝트/위키/파일, `/-/` 없는 구형 형식, `#note_n`, `#L10-20` | 기존 문서 GitLab URL 전부 분류 (단위 테스트) |
| 3 | GitLab 프로바이더 (P0 유형), 버전별 필드 호환 | 유형별 실제 URL 1건씩 조회 |
| 4 | `link_previews` 캐시, TTL, stale-while-revalidate, 강제 갱신 | 만료 캐시 즉시 반환 + 갱신 확인 |
| 5 | 일괄 API (로그인 필요), 호스트당 동시성 제한 | 익명 401 |
| 6 | 렌더러: 카드/칩/배지/hover 팝오버, 스켈레톤, 실패 시 일반 링크, 익명용 파싱 결과 칩 | [F10](../features/F10-link-preview.md) 2절 표 |
| 7 | P1: 위키, 파일 스니펫(F9 컴포넌트 재사용), OpenGraph 프로바이더 + SSRF 방어 | SSRF 차단 테스트 |
| 8 | MCP `upload_attachment`의 `source_url` (7번 SSRF 방어 재사용, 10MB 상한) — [F6](../features/F6-mcp-api.md) 3.2절 | 사설 IP URL 거부 |

## 진행 상황 (2026-11-18)

- 2~8 구현 완료. 가짜 GitLab 서버 통합 테스트, 기존 문서 GitLab URL 196건 전부 분류(미지원 0), 외부 OG·SSRF 차단 확인
- 1번 완료 (2026-11-20): 두 GitLab에 전용 계정 + `read_api` PAT (만료 2027-11). 실데이터 확인
  - `<gitlab-a>` 134건 중 133건 성공 (MR 79, 이슈 43, 프로젝트 4, 파일 3, 커밋 3, 위키 1). 1건은 하위 프로젝트 권한 없음(404)
  - `<gitlab-b>` 62건 전부 성공 (구형 `/commit/` 형식 포함)
  - 10절 성능: MR-Report 2026-W46 캐시 없음 0.52초 / 캐시 있음 9ms, `<gitlab-b>` 링크 최다 문서(47건) 1.24초
  - MR-Report 화면: MR 링크마다 상태 배지(Merged), hover 팝오버(작성자, 브랜치, 머지 상태, 댓글 수)

## 완료 기준

1. [F10](../features/F10-link-preview.md) 10절 완료 기준 전부 통과
