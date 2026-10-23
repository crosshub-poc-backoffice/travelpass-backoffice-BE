# DB를 BE로 옮기는 계획

목표: **DB의 주인은 BE 하나.** 테이블 구조(마이그레이션)도, 데이터를 읽고 쓰는 것도 BE가 하고,
FE는 BE API만 호출한다. FE에 남는 DB 접근은 로그인 세션(Session) 하나.

## 지금 상태 (2026-10-23, FE·BE PR #4 머지 직후)

| 구분 | 누가 하나 |
|---|---|
| 테이블 구조 | **FE** — `prisma/migrations` 56개. Vercel production 배포 때 `prisma migrate deploy`로 Supabase에 적용. BE는 `ddl-auto: none`으로 그 테이블을 그대로 씀 |
| 업무 쓰기 | 대부분 **BE** — 계정·예약 상태/견적/환불·문의 답변·상품·업체·정산·결제 설정·변경요청 |
| FE에 남은 쓰기 | 세션, 예약·문의 생성(TP 앱), 상품 번역, 검수 기록, 알림, 작업 이력·접속/로그인 로그 |
| FE에 남은 조회 | 대시보드 통계, 상품·예약·문의·검수·번역·알림·작업 이력 일부 (`lib/server/*`의 `prisma.*.find*`) |

### 지금 동작하지 않는 것 (FE `_old-api/`에 보관만 되어 있고 아무도 서빙하지 않음)
FE를 분리하면서 Next API 라우트를 `_old-api/app-api`로 옮겨 두기만 했다. 아래는 BE에도 FE에도 없다.

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
- **배포 순서 주의**: Supabase에 Prisma 마이그레이션 56개가 다 적용된 상태에서 BE를 배포해야 한다.
  BE는 Flyway가 advisory lock을 쓰므로 Supabase **Session pooler(5432) 또는 직접 연결**로 붙여야 한다
  (Transaction pooler 6543 금지).

### 2단계 — 돈이 걸린 쓰기: 예약·문의 생성 (TP 앱 API)
- `/api/v1/bookings`(생성·취소), `/api/v1/inquiries`, `/api/v1/inspect`를 BE 컨트롤러로. API 키 인증.
- 예약 생성 때 수수료·연맹 몫·PG 수수료 스냅샷 계산을 BE `Fees`로 일원화
  (지금은 FE `lib/server/bookings/service.ts`와 BE `Fees.java`에 계산이 따로 있다).
- idempotencyKey 중복 방지 유지.

### 3단계 — 알림
- 알림 조회·개수·읽음·전체 읽음·실시간 알림 닫기 API를 BE로. 알림 생성(`notifications/recipients.ts` 수신자 계산 포함)도 BE로 —
  BE의 쓰기 API가 일어난 자리에서 바로 만든다.
- cron 두 개(예약 자동 완료, 오래된 알림 정리)는 BE `@Scheduled`로. `vercel.json` crons 제거.

### 4단계 — 상품 번역·문서 자동입력·파일
- 번역(Upstage Solar), 상품 문서 자동입력, 상품 사진·사업자등록증 업로드를 BE로.
  Upstage 키·S3(Supabase Storage/RustFS) 키는 BE에만 둔다. FE `docs/upstage-api.md`의 함정 목록을 그대로 옮긴다.
- 검수 기록(`ProductInspection`)도 여기서.

### 5단계 — 남은 조회와 로그
- 대시보드 통계, 작업 이력·접속/로그인 로그 조회를 BE API로.
- 작업 이력 쓰기는 지금 FE·BE 양쪽에서 한다 → BE로 통일. 접속 로그(`AccessLog`)·로그인 로그는
  FE가 BE에 기록 API를 호출하는 방식으로.

### 6단계 — 정리
- FE에서 `prisma` 제거. Session만 남기는 방법 둘 중 택1:
  (a) Session 조회·생성·삭제를 BE 내부 API로 (FE는 DB 접속 정보가 아예 없어진다),
  (b) FE 전용 작은 저장소(Redis 등)로 분리.
  기본안은 (a) — 새 인프라가 필요 없다.
- FE `prisma/`, `lib/generated/prisma`, `_old-api/`, `lib/server/*`의 남은 DB 코드 삭제.
- FE `.env`에서 `DATABASE_URL`·`DIRECT_URL` 제거.

## 원칙
- 단계마다 PR 하나. 각 PR은 "FE가 BE API를 부르도록 바꾸기 + BE 구현 + 역할별 범위 테스트"를 같이 담는다 (PR #2~#4와 같은 방식).
- 스키마 변경은 이제 BE `src/main/resources/db/migration/V<n>__<설명>.sql`로만. 머지된 파일은 고치지 않는다.
- 각 단계가 끝나면 이 문서의 "지금 상태" 표를 갱신한다.
