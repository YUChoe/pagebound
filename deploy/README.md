# Pagebound 배포·전환 절차

관련: [spec/phases/M8-cutover.md](../spec/phases/M8-cutover.md), [spec/features/F8-migration.md](../spec/features/F8-migration.md)

사내 주소는 자리표시자(`<legacy-wiki>`, `<gitlab-a>`, `<gitlab-b>`)로 적는다. 실제 값은 저장소 밖에서 관리한다.

- 빠른 안내(옮길 파일과 실행 명령만): [SERVER-SETUP.md](SERVER-SETUP.md)
- GitLab 토큰 발급(링크 미리보기): [GITLAB-TOKEN.md](GITLAB-TOKEN.md)

## 1. 요구 사항

| 항목 | 값 |
|------|----|
| OS | Linux (systemd) |
| Java | 21 (`/usr/bin/java`가 21이 아니면 `pagebound.service`의 `ExecStart` 경로 수정) |
| 디스크 | 데이터 약 130MB(DB 53MB + 첨부 78MB) + 백업 7일분 약 1GB |
| 포트 | 4000 (레거시 Wiki.js 대체, Q8) |

## 2. 빌드 (개발 PC)

```bash
cd web && npm ci && npm run build && cd ..
mvn clean package -DskipTests      # clean 필수: 모듈 변경 없이 core만 바뀌면 jar 안의 core가 갱신되지 않는다
# 산출물
#   pagebound-server/target/pagebound-server-0.1.0-SNAPSHOT.jar
#   pagebound-migrator/target/pagebound-migrator-0.1.0-SNAPSHOT.jar
```

## 3. 설치 (서버, 최초 1회)

```bash
sudo useradd --system --home /opt/pagebound --shell /usr/sbin/nologin pagebound
sudo mkdir -p /opt/pagebound/{config,data,backups,logs}
sudo cp pagebound-server-0.1.0-SNAPSHOT.jar /opt/pagebound/pagebound-server.jar
sudo cp pagebound-migrator-0.1.0-SNAPSHOT.jar /opt/pagebound/pagebound-migrator.jar
sudo cp deploy/application.yml.example /opt/pagebound/config/application.yml
sudo cp deploy/pagebound.env.example /opt/pagebound/pagebound.env && sudo chmod 600 /opt/pagebound/pagebound.env
sudo chown -R pagebound:pagebound /opt/pagebound
sudo cp deploy/pagebound.service /etc/systemd/system/ && sudo systemctl daemon-reload
```

- `pagebound.env`에 GitLab 토큰을 넣는다 (없으면 링크 미리보기만 비활성). 발급은 [GITLAB-TOKEN.md](GITLAB-TOKEN.md)
- 관리자 계정은 Wiki.js 사용자가 그대로 이전된다 (기존 비밀번호로 로그인)

## 4. 데이터 이전 (리허설·전환 공통)

```bash
sudo -u pagebound java -jar /opt/pagebound/pagebound-migrator.jar \
  --source /path/to/wikijs-export/database.sqlite --target /opt/pagebound/data [--force]
less /opt/pagebound/data/migration-report.md
```

리포트에서 확인할 기대값 (F8 6절, 2026-11-18 리허설 결과와 비교):

| 항목 | 기대값 | 리허설 |
|------|--------|--------|
| 문서 | 195 | 195 |
| alias (원본 path) | 168/168 | 168/168 |
| 첨부 sha256 불일치 | 0 | 0/177 |
| 본문 첨부 참조 누락 | 0 | 0/178 |
| `<details>` 잔존 / collapsed 코드블록 | 0 / 26 | 0 / 26 |
| ```` ```diagram ```` 잔존 / SVG | 0 / 22 | 0 / 22 |
| 리비전 | 1,804 | 1,804 |

최신 export로 하면 문서·리비전 수는 늘어날 수 있다. 0이어야 하는 항목만 엄격히 본다.

## 5. 기동·자동 기동

```bash
sudo systemctl enable --now pagebound
systemctl status pagebound
curl -s http://localhost:4000/api/v1/me     # {"anonymousRead":true,...}
sudo reboot                                 # 재부팅 후 자동 기동 확인 (M8 1번 검증)
```

검색 색인은 첫 검색 때 전체를 만든다 (약 1초).

## 6. 백업·복구

- 매일 03:30에 `backups/{yyyyMMdd-HHmmss}/`로 백업한다: `pagebound.db`(SQLite `VACUUM INTO` 온라인 백업)와 `attachments/` 복사본. 7일이 지난 백업은 지운다 (최신 1개는 항상 남김)
- 즉시 실행: 관리 → 유지보수 → `백업 즉시 실행`. 마지막 백업 시각은 관리 대시보드에 나온다

복구:

```bash
sudo systemctl stop pagebound
sudo -u pagebound mv /opt/pagebound/data /opt/pagebound/data.broken
sudo -u pagebound mkdir /opt/pagebound/data
sudo -u pagebound cp -r /opt/pagebound/backups/<이름>/pagebound.db /opt/pagebound/backups/<이름>/attachments /opt/pagebound/data/
sudo systemctl start pagebound
```

## 7. 전환 (M8 전환 절차)

1. Wiki.js 편집 공지 → 읽기 전용 전환 (관리자 권한으로 ems 그룹 write 해제)
2. 최종 export → 4절 마이그레이터 실행(`--force`) → 리포트 확인
3. Wiki.js 중지 → `sudo systemctl start pagebound` (포트 4000으로 `<legacy-wiki>` 대체)
4. 레거시 URL 샘플 확인: `deploy/check-legacy-urls.sh http://<legacy-wiki> database.sqlite 10` (sqlite3, python3, curl 필요) → 10/10 OK, 로그인 확인
5. 공지
6. 롤백 기간 종료 후 Wiki.js 제거. 최종 `database.sqlite`는 읽기 전용 보관

### 수용 테스트 체크리스트 (M8 4번, 팀 사용자 3명 이상)

- [ ] 문서 조회, 트리 탐색, 레거시 URL 북마크 접속
- [ ] 새 문서 작성 + 스크린샷 붙여넣기 (첨부 저장)
- [ ] 문서 이동 (다른 문서의 링크 자동 갱신)
- [ ] 태그 추가·분류 화면
- [ ] 검색 (`in:code`, `tag:` 포함)
- [ ] 코드 접기 → 새로고침 후 유지
- [ ] MR 링크 미리보기 (GitLab 토큰 설정 후)
- [ ] MCP 1회: 설정 → API 토큰 발급 → `claude mcp add ...` → 문서 검색·조회

### 롤백

- 조건: 전환 당일 치명 결함 (로그인 불가, 문서 열람 불가, 데이터 누락)
- 절차: `sudo systemctl stop pagebound` → Wiki.js 기동 → ems 그룹 write 복구
- 전환 이후 Pagebound에서 바뀐 내용은 최근 변경(`/recent`, MCP `recent_changes`)으로 목록을 뽑아 수동 반영
