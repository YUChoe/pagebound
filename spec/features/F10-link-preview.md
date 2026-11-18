# F10. 웹 링크 미리보기 (R9)

관련: [00 현행 분석](../00-analysis.md) 9절, [02 link_previews](../02-data-model.md), [F5 문서 화면](F5-page-view.md), [F9 코드블록](F9-code-blocks.md), 단계 [M6](../phases/M6-link-preview.md)

## 1. 현행 근거

| 항목 | 값 |
|------|----|
| 외부 링크 (코드블록 제외) | 352건: `[텍스트](URL)` 263 / 문장 속 URL 75 / URL 단독 줄 14 |
| GitLab `<gitlab-a>` | MR 104, 이슈 49, 커밋 3, 파일(blob) 3, 위키 1, 프로젝트 4 |
| GitLab `<gitlab-b>` | 이슈 5, 커밋 7, 위키 7, 파일 2, 프로젝트 등 41. 일부 구형 URL (`/-/` 없음, 예: `/{ns}/{proj}/commit/{sha}`) |
| 주 사용처 | `git/MR-Report/2026-Wxx` 주간 리뷰 문서 (문서당 MR 링크 다수) |
| GitLab 접근 | 두 서버 모두 GitLab Enterprise Edition, 위키 서버에서 접근 가능. API 미인증 401, 프로젝트 비공개(미인증 404) → OG 태그 수집 불가, API 토큰 필수 |

## 2. 표시 형태

| 본문 형태 | 표시 |
|----------|------|
| URL 단독 줄 (문단에 URL 하나만) | 카드 |
| 문장 속 URL, 목록 항목 속 URL, `<URL>` | 인라인 칩: `[아이콘] project!13 제목 [상태]` |
| `[텍스트](URL)` | 작성자 텍스트 유지 + 앞 아이콘 + 뒤 상태 배지 |
| 모든 미리보기 대상 링크 | hover 시 카드 내용 팝오버 (300ms 지연) |

- 카드 표시를 피하려면 `<URL>` 형태로 작성 (단독 줄이어도 인라인 칩)
- 목록 안의 URL은 칩으로만 표시 (MR-Report처럼 링크가 많은 목록에서 카드 나열 방지)
- 조회 실패/미지원 URL: 일반 링크 그대로

## 3. 지원 대상과 표시 항목

| 유형 | URL 패턴 (`/-/` 없는 구형 포함) | GitLab API | 카드 항목 | 우선순위 |
|------|-------------------------------|-----------|----------|---------|
| MR | `/{ns}/{proj}/-/merge_requests/{iid}[/diffs…][#note_n]` | `GET /projects/:id/merge_requests/:iid` | `proj!iid`, 제목, 상태(Open/Draft/Merged/Closed), 작성자, 리뷰어, `source → target` 브랜치, 파이프라인 상태, 머지 가능 여부(`detailed_merge_status`, 미지원 버전은 `merge_status`), 라벨, 댓글 수, 갱신 시각 | P0 |
| 이슈 | `/-/issues/{iid}` | `GET /projects/:id/issues/:iid` | `proj#iid`, 제목, 상태(Open/Closed), 담당자, 라벨, 마일스톤, 마감일, 댓글 수 | P0 |
| 커밋 | `/-/commit/{sha}` | `GET /projects/:id/repository/commits/:sha` | short sha, 커밋 제목, 작성자, 시각 | P0 |
| 프로젝트 | `/{ns}/{proj}` | `GET /projects/:id` | 프로젝트명, 설명, 기본 브랜치, 최근 활동 시각 | P0 |
| 위키 | `/-/wikis/{slug}` | `GET /projects/:id/wikis/:slug` | 위키 제목, 프로젝트 | P1 |
| 파일 | `/-/blob/{ref}/{path}[#L10-20]` | `GET /projects/:id/repository/files/:path/raw?ref=` | 경로, ref. `#L` 범위 지정 시 해당 줄 스니펫(최대 30줄, [F9](F9-code-blocks.md) 코드블록 컴포넌트, `start=` 적용) | P1 |
| 일반 웹 | 그 외 `http(s)` | - (OpenGraph/`<title>`) | 제목, 설명, 사이트명, favicon, og:image | P1 |

- `:id`는 URL 인코딩된 프로젝트 경로 (`group%2Fproject`)
- `#note_n` 링크: 카드에 "댓글 링크" 표시만 (댓글 내용 미리보기는 백로그)

## 4. 프로바이더 설정

```yaml
pagebound:
  link-preview:
    gitlab:
      - name: gitlab-a
        base-url: http://<gitlab-a>
        token: ${GITLAB_A_TOKEN}      # 서비스 계정, scope read_api
      - name: gitlab-b
        base-url: http://<gitlab-b>
        token: ${GITLAB_B_TOKEN}
    opengraph:
      enabled: true                    # Q12 확정: 서버 외부 인터넷 접근 가능
      allow-private-hosts: []          # 사설 IP 대역 중 OG 수집 허용 호스트
    timeout: 3s
    per-host-concurrency: 4
```

- 토큰은 환경변수/외부 설정으로만 주입, DB·로그·API 응답에 노출 금지
- 기동 시 `GET /version`으로 GitLab 버전 확인 → 필드 호환 처리 (구형 서버 대응)
- 토큰 미설정 호스트: 미리보기 없이 일반 링크

## 5. 처리 흐름

1. 클라이언트 렌더러가 문서 내 미리보기 대상 URL 수집 (코드블록 제외)
2. `POST /api/v1/link-previews` 일괄 요청 (최대 100개). 카드/칩은 스켈레톤 표시
3. 서버: URL 정규화 → `link_previews` 캐시 조회
   - 유효: 즉시 반환
   - 만료: 기존 값 즉시 반환 + 백그라운드 갱신 (stale-while-revalidate)
   - 없음: 프로바이더 호출 (호스트당 동시 4건) 후 저장·반환
4. 카드 `⟳` 버튼: 해당 URL 강제 갱신

캐시 TTL:

| 대상 상태 | TTL |
|----------|-----|
| Open/Draft MR, Open 이슈 | 10분 |
| Merged/Closed MR, Closed 이슈, 위키, 프로젝트 | 24시간 |
| 커밋, sha ref 파일 | 30일 |
| 실패 (`not_found`, `forbidden`, `error`) | 5분 |

## 6. 권한과 보안

| 항목 | 사양 |
|------|------|
| 정보 노출 | 서비스 계정 토큰으로 조회한 비공개 GitLab 정보이므로 미리보기 API는 로그인 사용자만 호출 가능. 익명 사용자는 URL 파싱 결과(`project!13`)만 표시하고 제목·상태는 없음 (Q11 확정: 서비스 계정 공용 토큰) |
| SSRF | GitLab 프로바이더는 설정된 `base-url`로만 요청. OG 수집은 DNS 해석 후 사설/루프백/링크로컬 IP 차단, 리다이렉트마다 재검사, `allow-private-hosts`만 예외 |
| 응답 제한 | 타임아웃 3초, 본문 최대 1MB, HTML은 `<head>`만 파싱 |
| 렌더 | 프로바이더 응답 문자열은 텍스트로만 삽입 (HTML 미해석). og:image는 `referrerpolicy="no-referrer"` |

## 7. 에디터

- URL 붙여넣기: 그대로 삽입 (빈 줄이면 카드, 문장 중이면 칩으로 미리보기에 표시)
- 텍스트 선택 상태 URL 붙여넣기: [F1](F1-editor.md) 규칙대로 `[선택텍스트](URL)`
- 미리보기 패널에서 동일 렌더

## 8. MCP

- `get_page`는 원문만 반환. 링크 미리보기 도구는 제공하지 않음 (에이전트는 GitLab을 직접 조회)

## 9. API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/link-previews` | `{urls: []}` → `[{url, kind, status, data}]` (로그인 필요) |
| POST | `/api/v1/link-previews/refresh` | `{url}` 강제 갱신 |

## 10. 완료 기준

| 항목 | 기준 |
|------|------|
| MR-Report | `git/MR-Report/2026-W46`의 MR 링크 전부 칩 + 상태 배지 표시 |
| 성능 | 해당 문서 캐시 없음 3초 이내, 캐시 있음 300ms 이내 미리보기 완료 |
| 구형 URL | `<gitlab-b>`의 `/{ns}/{proj}/commit/{sha}` 형태 커밋 미리보기 |
| 단독 줄 | URL 단독 줄 14건 카드 표시 |
| 익명 | 로그아웃 상태에서 제목·상태 미표시, API 401 |
| 실패 | 삭제된 MR 링크는 일반 링크로 표시, 5분 후 재시도 |
| SSRF | OG 대상 `http://127.0.0.1`, `http://169.254.169.254` 차단 |
