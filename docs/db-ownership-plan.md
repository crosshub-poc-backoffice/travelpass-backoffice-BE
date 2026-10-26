# DB를 BE로 옮기는 계획

목표: **DB의 주인은 BE 하나.** 테이블 구조(마이그레이션)도, 데이터를 읽고 쓰는 것도 BE가 하고,
FE는 BE API만 호출한다. FE에 남는 DB 접근은 로그인 세션(Session) 하나.

## 지금 상태 (2026-10-26, "조회 → BE" PR 직후)

| 구분 | 누가 하나 |
|---|---|
| 테이블 구조 | **BE** — Flyway `V1`(Prisma 56개 기준선)·`V2`(customerId)·`V3`(ProductInspection) |
| 업무 쓰기 | **BE 전부** (알림 생성·번역·검수·변경요청 반영·작업 이력 포함) |
| 업무 조회 | **BE 전부** — 상품 목록(번역 상태·최근 변경요청·번역 상품명 검색 포함)·번역 화면·정원 캘린더·대시보드·판매 통계·작업 이력/로그인/접근/계정 변동/업체 이력·소속 목록·알림 확인표시 |
| 배치 | **BE** `@Scheduled` |
| FE에 남은 DB 접근 | 로그인 로그(`LoginLog`)·접근 기록(`AccessLog`) **쓰기** 둘뿐 (`lib/server/audit/access.ts`) |

### (끝남) 한때 동작하지 않던 것
FE를 분리하면서 Next API 라우트를 `_old-api/app-api`로 옮겨 두기만 했던 것들. 아래는 전부 BE 로 옮겨졌고 `_old-api/` 는 지웠다.

| 경로 | 쓰는 곳 |
|---|---|
| `/api/notifications`, `/count`, `/[id]/read`, `/read-all`, `/live-dismiss` | `components/shell.tsx` 상단 알림 |
| `/api/translate` | 문의 화면 번역 |
| `/api/products/extract`, `/api/products/images` | 상품 등록 폼 (문서 자동입력·사진) |
| `/api/partners/license` | 업체 화면 사업자등록증 |
| `/api/v1/bookings`, `/[id]/cancel`, `/api/v1/inquiries`, `/[id]`, `/api/v1/inspect` | TP 앱 연동 (API 키) |
| `/api/cron/bookings-complete`, `/api/cron/notifications-cleanup` | `vercel.json` cron |

→ 이 단계들(3~5)은 "옮기기"이면서 동시에 "고장난 기능 되살리기"다.

## 단계

### 1단계 — 테이블 구조의 주인을 BE로 (이번 PR)
- BE에 Flyway. `V1__baseline_from_prisma.sql` = Prisma 마이그레이션 56개를 빈 DB에 적용하고 뜬 구조
  (Prisma 스키마에 없는 `User_role_org_check` CHECK 제약까지 포함).
- 이미 있는 DB(로컬·Supabase)는 `baseline-on-migrate`로 V1을 "적용됨"으로 표시만 하고 실행하지 않는다.
  단, `beforeMigrate.sql`이 Prisma 마이그레이션 56개가 전부 적용됐는지 먼저 확인한다 — 덜 적용된 DB를
  기준선으로 찍어 버리는 사고를 막는다 (덜 적용됐으면 BE가 기동을 멈추고 안내 메시지를 낸다).
- FE: 새 Prisma 마이그레이션 금지. `prisma/migrations`는 옛 이력으로 그대로 두고(고치지 않음),
  `schema.prisma`는 Prisma Client 타입용으로만 쓴다 — BE에 V2 이상이 생기면 FE `schema.prisma`를 손으로 맞춘다.
  Vercel 빌드의 `migrate deploy`, `db:migrate`·`db:deploy`·`db:reset` 스크립트 제거.
  `npm run dev`는 마이그레이션 대신 "BE가 한 번이라도 떠서 Flyway가 돌았는지"만 확인한다.
- 로컬 Docker(Postgres·사진 저장소) compose 파일도 FE에서 BE `infra/local/`로 옮겼다. compose 이름(`travelpass-backoffice`)이 같아서 기존 컨테이너·볼륨을 그대로 쓴다. BE `bootRun`이 이 파일로 컨테이너를 켜고(spring-boot-docker-compose, 개발 실행 전용), FE `npm run dev`는 접속만 확인한다.
- **배포 순서 주의**: Supabase에 Prisma 마이그레이션 56개가 다 적용된 상태에서 BE를 배포해야 한다.
  BE는 Flyway가 advisory lock을 쓰므로 Supabase **Session pooler(5432) 또는 직접 연결**로 붙여야 한다
  (Transaction pooler 6543 금지).

### 2단계 — 돈이 걸린 쓰기: 예약·문의 생성 (끝 — PR #6)
- TP 앱 예약·문의는 BE 고객용 API(`/api/customer/**`, idblock realm 토큰)로 대체했다. FE `_old-api`는 삭제.
- 예약 생성 때 수수료·연맹 몫·PG 수수료 스냅샷 계산은 BE `Fees`.

### 3단계 — 알림·배치 (끝 — PR #7)
- 알림 조회·개수·읽음·전체 읽음·실시간 알림 닫기·확인표시 조회는 BE. 알림 생성(수신자 계산 포함)도 BE `Notifier` —
  BE의 쓰기(상태 전환·변경요청 생성/승인/반려·견적 응답·크로스허브 직접 처리)가 일어난 자리에서 같은 트랜잭션으로 만든다.
- cron 두 개(예약 자동 완료, 오래된 알림·접근 기록 정리)는 BE `@Scheduled`(`BookingCompletionJob`·`NotificationCleanupJob`). `vercel.json` 제거.
- 변경요청 승인은 BE 가 승인 표시와 반영(수정·삭제·전환)을 한 트랜잭션에서 한다.

### 4단계 — 상품 번역·검수 (끝 — PR #7) / 문서 자동입력·파일 (남음)
- 번역(Upstage Solar)·원문 언어 감지·사람이 고친 번역 저장·검수(규칙 + AI)는 BE (`product/translation`, `product/inspect`, `ai/SolarClient`).
  저장 뒤 번역·검수는 커밋 후 백그라운드. `UPSTAGE_API_KEY` 는 BE `.env`.
- 검수 기록 테이블 `ProductInspection` 은 FE Prisma 스키마에만 있고 마이그레이션이 없어 DB 에 없었다 → `V3`.
- **남은 것**: 상품 문서 자동입력(`/api/products/extract`), 문의 화면 번역(`/api/translate`), 상품 사진·사업자등록증 업로드를 BE 로.
  이게 끝나야 FE 에서 Upstage·S3 키가 빠진다.

### 5단계 — 남은 조회와 로그 (조회 끝 — 이번 PR / 로그 쓰기 남음)
- 조회는 전부 BE: 상품 목록(번역 상태·최근 변경요청·번역 상품명 검색)·번역 화면·정원 캘린더·대시보드(심사·변경요청·견적·환불·문의)·
  판매 통계(`report/StatsService`, FE 계산 1:1 포트)·작업 이력/로그인/접근/계정 변동/업체 이력(`report/ActivityLogService`)·소속 목록·연맹 선택지.
- 작업 이력 쓰기는 BE 로 통일됐다.
- **남은 것**: 접근 기록(`AccessLog`)·로그인 로그(`LoginLog`) 쓰기 — FE 가 BE 에 기록 API 를 호출하는 방식으로.

### 6단계 — 정리
- 로그 쓰기까지 옮기면 FE 에서 `prisma` 를 뺀다: `prisma/`, `lib/generated/prisma`, `lib/db.ts`, `scripts/dev.mjs` 의 DB 확인·시드.
  FE 시드(`prisma/seed.ts`)는 BE 데모 시드로 옮긴다.
- 세션은 이미 FE 전용 Redis 로 분리됐다(PR #6). `Session` 테이블은 V2 에서 삭제.
- FE `.env`·Vercel 에서 `DATABASE_URL`·`DIRECT_URL` 제거, FE 용 DB 계정 회수. 이게 "FE 가 BE DB 에 못 붙는다"의 실제 잠금이다.

## 원칙
- 단계마다 PR 하나. 각 PR은 "FE가 BE API를 부르도록 바꾸기 + BE 구현 + 역할별 범위 테스트"를 같이 담는다 (PR #2~#4와 같은 방식).
- 스키마 변경은 이제 BE `src/main/resources/db/migration/V<n>__<설명>.sql`로만. 머지된 파일은 고치지 않는다.
- 각 단계가 끝나면 이 문서의 "지금 상태" 표를 갱신한다.
