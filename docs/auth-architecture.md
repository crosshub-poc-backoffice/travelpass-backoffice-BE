# 인증 서버 · 리소스 서버 구조

2026-10-26 정리. DB 이전 계획(`db-ownership-plan.md`)과 별개로, 로그인·권한 구조에 대해 정한 것.
흐름 그림: https://claude.ai/artifact/M8TSkZJnxA3wpMntJ6D5TL (비공개 — 보려면 공유 필요)

## 구성요소와 역할

| 구성요소 | 역할 | 갖는 것 |
|---|---|---|
| **인증 서버** (Keycloak) | 인증 — "이 사람이 누구인가". OIDC로 access·refresh·id 토큰 발급 | Keycloak + 전용 DB(사용자·비밀번호·로그인 세션), realm 설정, 연동 가이드 |
| **리소스 서버** (제휴사 BE + DB) | 인가 — "이걸 해도 되는가". access 토큰을 검사하고 데이터를 내줌 | 업무 DB(Flyway), 백오피스 API, 고객용 API(만들 것) |
| **제휴사 FE** (Next.js) | 직원용 화면. 토큰을 받아 리소스 서버를 부름 | 자기 세션 저장소(Redis, 만들 것) |
| **TP 앱** (React Native, 아직 없음 — 지금은 브라우저 데모 `demo/tp-web`) | 고객용 앱. 토큰을 받아 리소스 서버를 부름 | 휴대폰 secure storage(Keychain/Keystore) |

## 원칙

1. **인증 서버와 리소스 서버는 독립.** DB도 코드도 따로다. 리소스 서버가 인증 서버에 기대는 건
   토큰 서명 확인용 공개키(JWKS)를 가끔 받아 오는 것 하나뿐이다.
2. **인증 서버는 별도 레포로 분리.** BE 레포에 있던 `infra/keycloak/`(compose·realm JSON·스크립트)을 `keycloak-auth` 레포로
   옮겼다(2026-10-26, 히스토리 포함). 여기에 고객 realm과 연동 가이드를 더한다. 업무 데이터·로직은 넣지 않는다.
3. **realm 두 개.** 직원 = `crosshub`(자가 가입 없음, 관리자가 발급), 고객 = `idblock`(자가 가입·소셜 로그인,
   나중에 "idblock으로 로그인"을 외부에 제공하는 쪽 — 그래서 realm 이름이 곧 그 브랜드다). 발급처가 달라 고객 토큰으로는 백오피스 API를 못 부른다.
   처음엔 Keycloak 하나에 realm 두 개, 사용자가 늘면 고객용을 별도 서버로.
4. **인가는 리소스 서버가 한다.** 두 가지를 같이 본다.
   - 무엇을: 기능별 read/write (예: 정산 지급완료·계정 발급은 크로스허브만)
   - 어떤 데이터에: 크로스허브 전체, 연맹은 대행 업체, 업체는 자기 업체, 고객은 본인 예약·문의(`Booking.customerId`·`Inquiry.customerId` = 토큰 sub)
   역할은 토큰과 DB가 같아야 하고, 범위는 DB가 정한다. BE에는 항상 access 토큰만 보낸다(id_token 금지).
5. **토큰을 받은 쪽은 알아서 보관한다.** 인증 서버는 보관 방식에 관여하지 않는다.
   - 제휴사 FE: 토큰을 서버 쪽에만 두고 브라우저엔 httpOnly 세션 쿠키만 (BFF). 브라우저에 토큰을 두지 않는다
     — XSS·확장 프로그램에 노출되고, 계정 정지·강제 로그아웃이 즉시 안 먹힌다.
   - TP 앱: secure storage. 자체 세션 없음, access 만료 시 refresh로 직접 갱신. secret·API 키를 앱에 넣지 않는다.
6. **제휴사 FE는 리소스 서버 DB를 쓰지 않는다.** 세션은 FE 전용 Redis(운영은 Vercel의 Upstash),
   로그인한 사람 정보(역할·업체 범위)는 BE `GET /api/v1/me`로 받는다. FE가 아는 건 Keycloak 주소와 BE API 주소뿐.
   2026-10-26 반영: 세션·토큰은 Redis(`lib/server/auth/session-store.ts`), 역할·업체 범위는 요청마다 `/me`. FE 의 Prisma `Session` 모델은 뺐다.
   아직 FE 가 DB 를 직접 쓰는 곳: 업무 조회(Prisma 22개 파일)·감사 기록(로그인 기록·접근 기록·마지막 로그인). 이건 별도 단계.
   → `db-ownership-plan.md` 6단계의 기본안("세션을 BE 내부 API로")을 이것으로 바꾼다.
7. **client는 토큰을 발급받는 쪽이다.** 제휴사 FE와 TP 앱이 client고, 제휴사 BE는 토큰을 검사하는 리소스 서버라
   로그인 client로 등록하지 않는다. 대신 BE 이름표 역할만 하는 client `backoffice-api`를 둔다
   (standard flow·direct grant·service account 모두 끔). 직원 역할은 이 client의 client role로 두고,
   FE·앱 client에는 audience mapper로 `backoffice-api`를 넣는다. BE는 토큰의 `aud`에 `backoffice-api`가
   있는지로 "우리한테 온 토큰인가"를 본다(azp 는 보지 않는다). 전에는 FE client(당시 `poc-backoffice`) 이름에 BE가 묶여 있어
   다른 client가 BE를 부르면 거절됐는데, 2026-10-26 에 `crosshub` realm 과 BE 를 이 구조로 바꿨다.
8. **BE는 issuer 두 개를 신뢰한다.** realm이 둘이므로 직원 토큰과 고객 토큰은 issuer가 다르다.
   BE는 issuer별로 검증기를 두고(Spring은 `JwtIssuerAuthenticationManagerResolver`), issuer를 보고
   직원 토큰 → 백오피스 API, 고객 토큰 → 고객용 API로 가른다. 고객 토큰으로 백오피스 API를 못 부르는 건
   여기서 보장된다. 2026-10-26 반영: `app.keycloak.staff-issuer`·`customer-issuer` 두 개를 읽어 issuer별 검증기를 두고,
   고객 토큰에는 `ROLE_CUSTOMER` 하나만 줘서 `/api/customer/**`만 열린다. 검증기는 JWKS 주소를 직접 지정해 지연 생성하므로
   BE 기동 때 Keycloak이 떠 있지 않아도 된다(첫 토큰 때 공개키를 받는다).

## client 구성 (realm별)

| realm | client | 종류 | 용도 |
|---|---|---|---|
| `crosshub` (직원) | `backoffice-web` | confidential, standard flow | 직원 로그인. 제휴사 FE(BFF)가 쓴다 |
| `crosshub` (직원) | `backoffice-api` | 모든 flow 끔 | BE 이름표. 직원 client role(`crosshub`·`affiliate`·`partner`) 보관, `aud` 대상 |
| `crosshub` (직원) | `backoffice-svc` | service account | 직원 계정 발급 때 BE가 Keycloak 관리 API를 부를 때 쓴다 |
| `idblock` (고객) | `tp-app` | public, PKCE | 고객 로그인. TP 앱이 쓴다 |
| `idblock` (고객) | `backoffice-api` | 모든 flow 끔 | `aud` 대상. 고객은 역할 없음, 본인 예약·문의만 |

- 토큰을 발급받는 client는 `backoffice-web`·`tp-app` 둘뿐이다. 나머지는 이름표(`backoffice-api`)거나 서버 간 호출용(`backoffice-svc`).
- `backoffice-api`는 두 realm에 같은 이름으로 둔다. BE 쪽 `aud` 검사 값이 하나로 유지된다.
- `crosshub` realm 은 이 표대로 되어 있다(2026-10-26). `backoffice-web` 는 full scope 가 꺼져 있어 scope mapping 으로 `backoffice-api` role 을 토큰에 담고,
  audience mapper 로 `aud` 에 `backoffice-api` 를 넣는다. `idblock` realm 도 같은 틀이다(2026-10-26): `tp-app` 에 audience mapper,
  `backoffice-api` 이름표. 고객은 role 이 없어 scope mapping 은 없다. 연동 가이드: keycloak-auth 레포 `docs/tp-app-integration.md`.

## 인증 서버가 쓰는 쪽에 주는 것 (연동 가이드 내용)

| 항목 | 예 |
|---|---|
| issuer 주소 | `https://<인증서버>/realms/<realm>` |
| client ID (서버가 있는 쪽만 secret) | 제휴사 FE = confidential, TP 앱 = public(PKCE) |
| 로그인 후 돌아갈 주소 | 제휴사 FE `…/api/auth/keycloak/callback`, TP 앱 `travelpass://callback` |
| scope·토큰 항목 | `openid profile email` → `sub`, 이메일, 이름. `aud`에 `backoffice-api` 포함(audience mapper). 직원은 `resource_access.backoffice-api.roles`에 client role |
| 토큰 수명 | access 짧게(분 단위), refresh 길게 |
| 테스트 계정 | realm별 테스트 사용자 |

## 남아 있는 연결 하나

직원 계정 발급: 백오피스 BE가 Keycloak 관리 API(`backoffice-svc` service account, Client Credentials)를 부른다.
방향은 백오피스 → 인증 서버 한쪽뿐이고, 권한은 백오피스 client에만 좁혀 두었다. 고객은 스스로 가입하므로 이 연결이 없다.

## 할 일 (순서)

| # | 할 일 | 어디 |
|---|---|---|
| 1 | ~~인증 서버 레포 만들고 `infra/keycloak` 옮기기~~ → 끝. GitHub `crosshub-poc-backoffice/keycloak-auth`(private), 직원 로그인 재확인 완료 | 새 레포 |
| 2 | ~~`crosshub` realm에 `backoffice-api` client 추가, 직원 role 이동, FE client 에 audience mapper. BE 의 aud 검사·role 경로를 `backoffice-api` 로~~ → 끝(realm JSON·BE 코드·테스트). 로컬 Keycloak 에 적용(`down -v` 후 up, 비번 재설정)만 남음 | 새 레포·제휴사 BE |
| 3 | ~~고객 realm + TP 앱 client(public, PKCE) + 연동 가이드~~ → 끝(`idblock` realm, `tp-app`, `docs/tp-app-integration.md`). 소장님께 전달만 남음 | 새 레포 |
| 4 | ~~FE 세션 저장소를 Redis로, 사용자 정보는 BE `/me`로~~ → 끝. 로컬 Redis 는 FE `infra/redis`, 운영은 `REDIS_URL` 에 Upstash. DB 의 `Session` 테이블은 더 안 쓴다 — BE Flyway 로 지우는 건 V2 에서 | 제휴사 FE·BE |
| 5 | ~~BE issuer 두 개 신뢰 + 고객용 API(상품·예약·문의) + 예약·문의에 고객 ID 연결(Flyway V2)~~ → 끝(2026-10-26). `customerId` = 토큰 sub, 조회는 "내 것"만. 예약 신청 규칙은 FE 의 옛 앱 API(createBookingFromApp)를 그대로 옮김(정원 판정 포함). V2 에서 `Session` 테이블 삭제 | 제휴사 BE |
| 6 | TP 앱이 아직 없어 브라우저 데모(keycloak-auth `demo/tp-web`)로 고객 로그인→BE 호출을 보여준다. 끝. `tp-app` client 에 `http://localhost:5173` redirect 가 데모용으로 들어 있다(운영 realm 에서는 뺀다) | 새 레포 |

정할 것: 앱 결제 포함 여부.
정한 것: 고객 realm 이름 = `idblock` (2026-10-26. 배포 전이라 바꾸려면 지금). **고객 예약은 로그인 필수** (2026-10-26) → 예약·문의는
항상 고객 `sub` 에 연결되고, 고객용 API 는 "내 예약·내 문의" 만 내준다. 비회원 예약 경로는 만들지 않는다.
인증 서버 레포 위치 = GitHub 조직 `crosshub-poc-backoffice` (BE·FE 와 같은 곳).
