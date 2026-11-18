# M1. 골격, 스키마, 마이그레이터

- 선행: M0 (결정 사항 확정, 완료)
- 예상: 2일
- 범위: [01 아키텍처](../01-architecture.md) 2~3절, [02 데이터 모델](../02-data-model.md) 전체, [F8 마이그레이션](../features/F8-migration.md) 전체, [F2](../features/F2-attachments.md) 1절(저장 위치)

## 작업

| # | 작업 | 산출물 | 검증 |
|---|------|--------|------|
| 1 | Maven 멀티모듈 생성 (`pagebound-core`, `pagebound-server`, `pagebound-migrator`), Spring Boot/Spring AI BOM 버전 확정 | `pom.xml` | `mvn -q package` 성공 |
| 2 | Flyway `V1__init.sql` (DDL + FTS 트리거), SQLite PRAGMA 설정 | 마이그레이션 스크립트 | 빈 DB 생성 후 테이블 10개(+FTS) 확인 |
| 3 | `MarkdownAnalyzer` (flexmark): 위키 링크 추출(코드 제외), 섹션 분할, 목차, 평문 추출 | `pagebound-core` | 단위 테스트: bash `[[ ]]` 제외, setext/ATX 헤딩, 코드펜스 내 `#` 무시 |
| 4 | 저장소/서비스 최소 구현: 문서 생성(경로 계산), 리비전, 링크 인덱스, 첨부 저장 | `pagebound-core` | 단위 테스트 |
| 5 | 마이그레이터 단계 구현: users → 트리/문서 → alias → 리비전 → 첨부 → 태그 → 본문 변환 → links/FTS → 리포트 | `pagebound-migrator.jar` | 실데이터 실행 성공 |
| 6 | 자동 검증 ([F8](../features/F8-migration.md) 6절, M2 확인 항목 제외) | 검증 결과를 리포트에 포함 | 전 항목 통과 |

## 완료 기준

1. `java -jar pagebound-migrator.jar --source database.sqlite --target data/` 실행 → `pagebound.db`, `data/attachments/`, `migration-report.md` 생성
2. 문서 194, alias 168/168, 첨부 sha256 전 건 일치, 태그 조합 보존
3. 같은 명령 `--force` 재실행 시 동일 결과 (건수·해시 일치)

## 미포함

- 웹 화면, 로그인 (M2)
