# M8. 리허설, 전환

- 선행: M5, M6, M7
- 예상: 1일
- 범위: [01 아키텍처](../01-architecture.md) 4절(비기능), [F8](../features/F8-migration.md) 재실행, Q8(확정: 기존 서비스 대체)·Q9(확정: 병행 없음, 완전 이전)

## 작업

| # | 작업 | 검증 |
|---|------|------|
| 1 | 패키징: `pagebound-server.jar` + `application.yml` 외부화 + systemd 유닛 | 서버 재부팅 후 자동 기동 |
| 2 | 백업: `pagebound.db` online backup + `data/attachments/` 일 1회, 7일 보관 | 백업본으로 복구 후 기동 성공 |
| 3 | 리허설: 최신 Wiki.js export로 마이그레이터 재실행, 리포트 검토 | [F8](../features/F8-migration.md) 6절 전 항목 통과 |
| 4 | 수용 테스트: 팀 사용자 3명 이상, 체크리스트(조회, 붙여넣기 작성, 이동, 태그, 검색, 코드 접기, MR 링크 미리보기, MCP 1회) | 체크리스트 전 항목 통과 |
| 5 | 전환 절차 실행 (아래) | 레거시 URL 접속 시 신규 문서로 301 |

## 전환 절차

1. Wiki.js 편집 공지 및 읽기 전용 전환 (관리자 권한으로 ems 그룹 write 해제)
2. 최종 export → 마이그레이터 실행 → 리포트 확인
3. Wiki.js 중지, Pagebound를 `<legacy-wiki>`으로 기동 (Q8 확정)
4. 레거시 URL 샘플 10건 301 확인, 로그인 확인
5. 공지
6. 롤백 기간 종료 후 Wiki.js 서비스 제거 (병행 운영 없음). 최종 export `database.sqlite`는 읽기 전용으로 보관

## 롤백

- 조건: 전환 당일 치명 결함 (로그인 불가, 문서 열람 불가, 데이터 누락)
- 절차: Pagebound 중지 → Wiki.js 기동 → ems 그룹 write 복구
- 전환 이후 Pagebound에서 작성된 변경은 `recent_changes`로 목록화해 수동 반영
