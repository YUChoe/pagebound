# 서버 구동 빠른 안내

전체 절차(백업·복구, 전환, 롤백)는 [README.md](README.md). 이 문서는 "어떤 파일을 옮기고 어떤 명령으로 실행하는가"만 다룬다.

## 1. 준비물

| 항목 | 확인 |
|------|------|
| Java 21 | `java -version` → 21 |
| systemd | `systemctl --version` |
| 포트 | 4000 (Wiki.js와 같은 포트. Wiki.js가 떠 있는 동안 시험하려면 다른 포트) |

빌드 후 코드가 바뀌었다면 개발 PC에서 다시 만든다 (약 3분).

```bash
cd web && npm run build && cd ..
mvn clean package -DskipTests      # clean 필수 (README.md 2절)
```

## 2. 옮길 파일

| 저장소 파일 | 서버 위치 | 용도 |
|------------|----------|------|
| `pagebound-server/target/pagebound-server-0.1.0-SNAPSHOT.jar` (약 52MB, 웹 화면 포함) | `/opt/pagebound/pagebound-server.jar` | 서버 |
| `pagebound-migrator/target/pagebound-migrator-0.1.0-SNAPSHOT.jar` | `/opt/pagebound/pagebound-migrator.jar` | Wiki.js export 이전 (방법 B) |
| `deploy/application.yml.example` | `/opt/pagebound/config/application.yml` | 포트, 데이터·백업 경로 |
| `deploy/pagebound.env.example` | `/opt/pagebound/pagebound.env` | GitLab 토큰 ([GITLAB-TOKEN.md](GITLAB-TOKEN.md)). 비워도 기동됨 |
| `deploy/pagebound.service` | `/etc/systemd/system/pagebound.service` | 자동 기동 |
| `deploy/check-legacy-urls.sh` | 임의 위치 | 전환 후 레거시 URL 301 확인 |

데이터는 둘 중 하나:

- 방법 A: 이미 이전된 로컬 `data/` (`pagebound.db`, `attachments/`)를 그대로 복사 — 시험용
- 방법 B: Wiki.js export `database.sqlite`를 올리고 서버에서 migrator 실행 — 전환 시

## 3. 명령

```bash
# 1) 계정·디렉터리
sudo useradd --system --home /opt/pagebound --shell /usr/sbin/nologin pagebound
sudo mkdir -p /opt/pagebound/{config,data,backups,logs}

# 2) 파일 배치 (scp로 올린 뒤)
sudo cp pagebound-server-0.1.0-SNAPSHOT.jar /opt/pagebound/pagebound-server.jar
sudo cp pagebound-migrator-0.1.0-SNAPSHOT.jar /opt/pagebound/pagebound-migrator.jar
sudo cp application.yml.example /opt/pagebound/config/application.yml
sudo cp pagebound.env.example /opt/pagebound/pagebound.env && sudo chmod 600 /opt/pagebound/pagebound.env

# 3) 데이터
#   방법 A
sudo cp -r data/pagebound.db data/attachments /opt/pagebound/data/
#   방법 B
sudo java -jar /opt/pagebound/pagebound-migrator.jar --source database.sqlite --target /opt/pagebound/data
sudo chown -R pagebound:pagebound /opt/pagebound

# 4) 서비스 등록·기동
sudo cp pagebound.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now pagebound
systemctl status pagebound
curl -s http://localhost:4000/api/v1/me
```

## 4. 참고

- 로그인: Wiki.js 계정을 기존 비밀번호로 그대로 사용
- systemd 없이 확인만 할 때: `cd /opt/pagebound && java -jar pagebound-server.jar --server.port=8080`
- `/usr/bin/java`가 21이 아니면 `pagebound.service`의 `ExecStart` 경로를 바꾼 뒤 `sudo systemctl daemon-reload`
- 로그: `/opt/pagebound/logs/pagebound.log`, `journalctl -u pagebound -f`
- 재시작: `sudo systemctl restart pagebound` (설정·토큰 변경 후)
