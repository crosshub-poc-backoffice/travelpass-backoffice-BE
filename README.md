# travelpass-backoffice-BE

크로스허브 백오피스 백엔드 (Spring Boot, Java 17+, Gradle).
v1(Next.js 풀스택)에서 FE/BE를 분리하며 BE를 새로 세운 것. 첫 슬라이스는 **상품(products) 조회 API**.

## 전제
- v1(Prisma)이 쓰던 **Postgres를 그대로 재사용**. 테이블 구조의 주인은 **BE(Flyway)** — `src/main/resources/db/migration`.
  `V1__baseline_from_prisma.sql`이 Prisma 마이그레이션 56개까지의 구조이고, 이미 있는 DB는 첫 기동 때 V1을 baseline으로 표시만 한다.
  스키마 변경은 `V<n>__<설명>.sql`로만 (머지된 파일은 고치지 않는다). Hibernate는 `ddl-auto=none`. 이전 계획: `docs/db-ownership-plan.md`
- 인증은 **Keycloak tp-office realm**(별도 레포 `keycloak-auth`). 토큰의 `resource_access.tp-office-partner-api.roles`(BE 이름표 client `tp-office-partner-api`의 client role: `crosshub`/`affiliate`/`partner` — DB `User.role`과 같은 이름)를 1차 권한으로 매핑. 구조는 `docs/auth-architecture.md`.
- **권한 판단 기준은 DB(`User.role`)**. 토큰 role은 "이 API를 부를 수 있나" 거름망이고, 업체 범위·crosshub 전용 여부는 `CallerService`가 DB로 최종 판단. 계정 발급 시 토큰 role과 DB role을 같이 세팅한다.
- 토큰은 서명·만료·issuer + `aud`에 `tp-office-partner-api`가 있는지까지 검증 (로그인 client 쪽 audience mapper 가 넣어 준다. 없는 토큰은 거부).
- **issuer 두 개.** 직원 `tp-office` realm 토큰은 `/api/v1/**`, 고객 `tp` realm 토큰은 `/api/customer/**`(지금은 `GET /api/customer/me` 하나). 토큰 `iss` 로 가른다. 고객 토큰에는 role 이 없고 `ROLE_CUSTOMER` 하나만 준다. 고객 로그인 데모: keycloak-auth 레포 `demo/tp-web`.

## 실행
### 가장 쉬운 방법: docker compose (맥에 Java 17 이 없어도 된다)
```bash
cp .env.example .env               # <...> 칸(Keycloak svc secret) 채우기
docker compose up -d --build       # DB·파일 저장소·BE 앱 → http://localhost:8081 (처음엔 이미지 빌드로 몇 분)
docker compose logs -f app         # 로그
docker compose down                # 끄기 (데이터는 볼륨에 남음)
```
Keycloak 은 `keycloak-auth` 레포에서 `docker compose up -d --wait`, FE 는 `npm run dev`.
BE 앱(`app`)은 `.env` 의 `COMPOSE_PROFILES=app` 으로 같이 뜬다. `bootRun` 은 이 값을 덮어써서 DB·저장소만 켠다(`build.gradle`).
코드를 바꾼 뒤에는 `--build` 를 붙여 다시 올린다. 컨테이너 안에서 다른 컨테이너·내 맥으로 가는 주소(DB·저장소·Keycloak 접속)는
`compose.yaml` 이 바꿔 넣는다 — `.env` 는 맥에서 `bootRun` 할 때와 같은 파일을 그대로 쓴다.

### 개발하면서: ./gradlew bootRun (JDK 17 필요, 코드 바꾸면 바로 반영)
`docker compose` 로 띄운 BE 와 같은 8081 포트라 둘 중 하나만 켠다 (`docker compose stop app`).
순서: **BE `./gradlew bootRun` → FE `npm run dev`.**
`bootRun`이 로컬 DB(Postgres 5433)·사진 저장소(9000) 컨테이너를 알아서 켜고(`compose.yaml`, BE를 꺼도 컨테이너는 유지),
Flyway가 테이블을 맞춘다. Keycloak은 별도 레포 `keycloak-auth`(이 폴더 옆, 원래 여기 `infra/keycloak`에 있던 것)에서 처음 한 번만 켜 두면 Docker Desktop과 같이 뜬다.
BE는 Keycloak이 아직 안 떠 있어도 기동된다(첫 토큰 검증 때 공개키를 받아온다). 로컬 컨테이너 전체(Keycloak·DB·Redis)는 `docs/local-infra.md`.
원격 DB로 띄울 때는 `.env`에 `DOCKER_COMPOSE_ENABLED=false`.
```bash
cp .env.example .env    # <...> 칸(Keycloak svc secret) 채우기. 빠진 값이 있으면 bootRun 이 이름을 알려주고 멈춘다
./gradlew bootRun                                  # 기본: 토큰 검증 켜짐
SPRING_PROFILES_ACTIVE=secure ./gradlew bootRun    # .env에 dev가 켜져 있어도 강제로 검증
# → http://localhost:8081
```
로컬 DB를 처음부터 다시 만들려면: `docker compose down && docker volume rm travelpass-backoffice_db-data && COMPOSE_PROFILES= docker compose up -d --wait` (이 폴더에서. `COMPOSE_PROFILES=` 는 앱 컨테이너 없이 DB·저장소만)
→ BE 기동(테이블 생성) → 데모 데이터 `cd demo && npm install && npm run seed`. 사진 저장소 볼륨(`storage-data`)은 그대로 남는다.
데모 데이터만 처음 상태로 되돌리려면 `demo/` 에서 `npm run reset` (`demo/README.md`).

로컬에서 토큰 없이 FE↔BE만 맞춰보려면 `.env`에 `APP_AUTH_DISABLED=true` (기동 로그에 경고가 뜬다. 배포 금지).
JDK 17 또는 21로 실행 (Gradle 8.14가 JDK 25를 지원하지 않는다 — `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`). 의존성 다운로드를 위해 인터넷 연결 필요(Maven Central).

## API (products)
| 메서드 | 경로 | 권한(토큰 role) | 업체 범위 | 설명 |
|---|---|---|---|---|
| GET | `/api/v1/products?status=` | crosshub/affiliate/partner | crosshub 전체, affiliate 대행 업체, partner 본인 업체 | 목록(요약) |
| GET | `/api/v1/products/{id}` | crosshub/affiliate/partner | 범위 밖이면 404 | 상세 |
| POST | `/api/v1/products` | crosshub/affiliate/partner | partner는 본인 업체로 강제, affiliate는 대행 업체만 | 생성(상태 review로 시작) |
| PUT | `/api/v1/products/{id}` | crosshub/affiliate/partner | 범위 안만 | 수정(보낸 필드만 반영) |

crosshub 전용: `/api/v1/accounts` (토큰 role + DB role 둘 다 확인).

**PoC 범위: 예약·결제·정산·판매 통계는 없다** (2026-10-29 코드 삭제). 플랫폼은 투어 정보를 게재하고 업체로의 문의까지만 받는다.
`Booking`·`Payment`·`Settlement`·`AffiliateSettlement`·`PgSetting` 테이블은 DB 에 남아 있지만 코드는 쓰지 않는다 (데모 시드는 아직 채운다).

`GET /api/v1/me`: 로그인한 직원의 id·loginId·이름·role·소속·업체 범위(managedPartners). FE(BFF)가 세션을 만들 때와 요청마다 부른다.
고객용(`tp` realm 토큰, `/api/customer/**`): `GET me`, `GET products`(노출중), `GET/POST inquiries`, `GET inquiries/{id}`. 전부 토큰 sub 의 "내 것"만. 표는 keycloak-auth 레포 `docs/tp-app-integration.md`.

모든 요청에 `Authorization: Bearer <access_token>` 필요.

**업체 범위는 어떻게 아나**: 토큰의 `preferred_username`(= Keycloak username)을 기존 `User` 테이블의
`loginId`로 찾아 그 사람의 `role`/`partnerId`/`affiliateId`를 얻는다(= "sub → 서비스 DB" 브리지). role=crosshub는 전체,
role=affiliate는 `Partner.proxyAffiliateId`가 자기 연맹인 업체, role=partner는 자기 업체만. → Keycloak username과 User.loginId를 같은 값으로 발급하는 것이 전제.

## 다음 단계
- bookings / inquiries / inspect 도메인 이관
- FE(`poc-backoffice-FE`)의 데이터 접근을 이 API로 repoint (현재 `_moved-to-BE/`에 옛 Next API가 보관됨)

## 설계 메모
- enum 컬럼(status/confirmType/category)은 Postgres native enum이나 `package`가 Java 예약어라 **String 매핑 + `stringtype=unspecified`**.
- jsonb 컬럼(schedule/cancelRules/priceTable/blocks)은 원본 JSON 문자열 보존(@JsonRawValue로 그대로 응답).
- 포트 8081 (FE 3000 / Keycloak 8080과 분리).
