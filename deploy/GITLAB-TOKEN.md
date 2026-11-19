# GitLab 토큰 발급 (링크 미리보기용)

관련: [spec/features/F10-link-preview.md](../spec/features/F10-link-preview.md) 4·6절, [spec/phases/M6-link-preview.md](../spec/phases/M6-link-preview.md) 1번

Pagebound는 문서 안 GitLab 링크(MR, 이슈, 커밋, 프로젝트, 위키, 파일)의 제목·상태를 GitLab API로 읽는다. GitLab마다 토큰 1개가 필요하다 (`<gitlab-a>`, `<gitlab-b>`).

## 1. 어떤 토큰을 쓰나

| 방식 | 판단 |
|------|------|
| 전용 계정 + 개인 액세스 토큰(PAT) | 권장. 토큰 1개로 여러 최상위 그룹을 읽을 수 있다. 일반 사용자 계정이면 라이선스 1석 사용 |
| 서비스 계정 + PAT | Premium 이상이면 이 방식이 더 좋다 (라이선스 미사용). 관리자가 서비스 계정을 만들고 PAT 발급 |
| 그룹 액세스 토큰 | 해당 그룹만 읽을 수 있다. 문서가 참조하는 최상위 그룹이 GitLab마다 7~8개라 호스트당 토큰 1개 구조에서는 쓸 수 없다 |

참조하는 최상위 그룹 목록은 사내 정보라 저장소 밖(`spec/local-values.md`)에 둔다.

## 2. 발급 절차 (전용 계정 + PAT)

GitLab 관리자가 한다. 두 GitLab에서 각각 반복한다.

1. 계정 만들기: Admin → Users → New user. 예: 이름 `Pagebound Preview`, 사용자명 `pagebound-preview`. 외부(External) 사용자로 두지 않는다
2. 권한 주기: 문서가 참조하는 최상위 그룹마다 Manage → Members → Invite members → `pagebound-preview`, 역할 Reporter
   - Reporter면 MR·이슈·커밋·위키·파일 읽기에 충분하다. Developer 이상은 주지 않는다
3. 토큰 만들기: `pagebound-preview`로 로그인(또는 관리자 Impersonate) → 오른쪽 위 아바타 → Edit profile → Access → Personal access tokens → Add new token (버전에 따라 Generate token → Legacy token)
   - Token name: `pagebound-link-preview`
   - Expiration date: 최대 365일 (GitLab 16.0부터 무기한 불가). 만료일을 일정에 등록
   - Scopes: `read_api` 하나만
4. 생성된 토큰(`glpat-...`)은 이때만 보인다. 바로 복사해 3절대로 서버에 넣는다

서비스 계정 방식이면 1번 대신 Admin → Service accounts에서 계정을 만들고, 3번은 관리자가 API로 PAT를 발급한다 (`POST /users/:id/personal_access_tokens`, scopes `read_api`).

## 3. 서버에 적용

```bash
sudo vi /opt/pagebound/pagebound.env
#   GITLAB_A_URL=http://<gitlab-a>
#   GITLAB_A_TOKEN=glpat-...
#   GITLAB_B_URL=http://<gitlab-b>
#   GITLAB_B_TOKEN=glpat-...
sudo systemctl restart pagebound
```

- 토큰은 이 파일(권한 600)에만 둔다. DB, 로그, API 응답, 저장소에 넣지 않는다
- 로컬 개발 서버라면 같은 이름의 환경변수를 설정하고 실행한다

## 4. 확인

```bash
# 토큰이 유효한지 (200 + 버전 JSON)
curl -s -H "PRIVATE-TOKEN: glpat-..." http://<gitlab-a>/api/v4/version
# 그룹 권한이 있는지 (문서에 있는 MR 하나로, 200이면 정상, 404면 그 그룹에 Reporter 없음)
curl -s -o /dev/null -w '%{http_code}\n' -H "PRIVATE-TOKEN: glpat-..." \
  "http://<gitlab-a>/api/v4/projects/<그룹>%2F<프로젝트>/merge_requests/<번호>"
```

- Pagebound 관리 → 설정 → "GitLab 연결"에 `HTTP 200` 과 버전이 나오면 적용된 것
- 로그인 상태로 MR 링크가 많은 문서(예: 주간 MR 리포트)를 열어 상태 배지가 나오는지 본다
- 이전에 실패한 링크는 5분 캐시가 지나야 다시 조회된다. 바로 보려면 관리 → 유지보수 → 링크 미리보기 캐시 비우기

## 5. 갱신·폐기

- 갱신: 만료 전 새 토큰 발급 → `pagebound.env` 교체 → `sudo systemctl restart pagebound` → 이전 토큰 폐기(Personal access tokens → Revoke)
- 유출 의심: GitLab에서 즉시 Revoke → 새 토큰 발급·적용. 토큰은 읽기 전용(`read_api`)이라 GitLab 데이터를 바꿀 수는 없다
- 토큰이 없거나 만료되면 미리보기만 꺼지고 링크는 일반 링크로 보인다 (위키 동작에는 영향 없음)
