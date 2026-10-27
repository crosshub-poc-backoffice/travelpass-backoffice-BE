# 데모 데이터 (로컬·데모 DB)

빈 DB에 데모 데이터(연맹·업체 6곳·상품 6개와 번역·예약·결제·문의·정산·알림·작업 이력·로그)를 넣는다.
날짜는 전부 "오늘(JST) 기준 며칠 전/후"라 언제 돌려도 대기 중인 일·이번 달 정산이 살아 있다.

```bash
cd demo
npm install
npm run seed     # 빈 DB(계정이 없을 때)만 채운다
npm run reset    # 모든 데이터를 지우고 데모로 다시 채운다 (로컬만, 원격은 --target=supabase + 확인 입력)
```

- BE 를 먼저 한 번 띄워(`./gradlew bootRun`) Flyway 로 표를 만들어 둬야 한다.
- 접속 주소는 BE `.env` 의 `DB_*` 를 읽는다. 다른 DB 는 `DEMO_DATABASE_URL` 로.
- 테스트 계정 비밀번호는 Keycloak 에 있다 — `keycloak-auth/scripts/reset-password.sh`.

FE 레포 `prisma/demo` 에서 옮겨 왔다 (FE 는 이제 DB 에 붙지 않는다). `lib/` 는 FE 의 금액·일정·번역 키 계산을
데모 데이터를 만들 때만 쓰려고 복사해 둔 것이다 — 앱 동작과는 상관없다. `db.ts` 가 Prisma 대신 pg 로 INSERT 한다.
