# 인증·리소스 구조 — 지금과 앞으로

2026-10-26. 노션 "인증·리소스 구조" 페이지와 같은 내용. 세부 결정은 `auth-architecture.md`.

> 세 덩어리로 나눴다. **인증 서버**는 로그인만, **리소스 서버**는 데이터만, **클라이언트**는 토큰 들고 왔다 갔다. 셋은 코드도 DB도 따로.

## 지금

```mermaid
flowchart LR
  subgraph KC["🔐 인증 서버 Keycloak"]
    direction TB
    R1["realm crosshub<br/>직원"]
    R2["realm idblock<br/>고객"]
  end
  subgraph C[" "]
    direction TB
    FE["제휴사 백오피스 FE"]
    APP["TP 앱<br/>(지금은 데모 웹)"]
  end
  REDIS[("Redis<br/>FE 로그인 세션")]
  subgraph RS["🗄️ 리소스 서버"]
    direction TB
    BE["BE<br/>이름표 backoffice-api"]
    DB[("업무 DB")]
  end

  R1 == "① OIDC Authorization Code + PKCE<br/>confidential client (secret 은 FE 서버에만)" ==> FE
  R2 == "① OIDC Authorization Code + PKCE<br/>public client (secret 없음)" ==> APP
  FE == "② Authorization: Bearer access_token<br/>/api/v1/**" ==> BE
  APP == "② Authorization: Bearer access_token<br/>/api/customer/**" ==> BE
  FE --- REDIS
  BE --- DB
  BE -. "③ JWKS 공개키로 서명 검증만" .-> KC

  classDef auth fill:#6C5CE7,stroke:#4834d4,color:#fff
  classDef app fill:#FDCB6E,stroke:#e1b12c,color:#2d3436
  classDef rs fill:#00B894,stroke:#019875,color:#fff
  classDef db fill:#636e72,stroke:#2d3436,color:#fff
  class R1,R2 auth
  class FE,APP app
  class BE rs
  class DB,REDIS db
  style C fill:#fff8e1,stroke:#e1b12c,stroke-dasharray:4
  style KC fill:#f3f0ff,stroke:#6C5CE7
  style RS fill:#e8f8f3,stroke:#00B894
```

- 직원은 `crosshub` realm, 고객은 `idblock` realm 으로 로그인. realm 이 다르면 사용자 풀이 다른 거라 고객 토큰으로 직원 API 못 부름.
- 로그인은 둘 다 OIDC Authorization Code + PKCE. 차이는 FE 는 서버(BFF)가 있어 client secret 도 같이 쓰고(토큰은 브라우저에 안 내림), 앱은 secret 을 둘 데가 없어 PKCE 만. 로그인 화면은 둘 다 Keycloak 이 그림.
- FE 로그인 세션은 FE 전용 Redis. FE 가 DB 를 직접 읽는 데가 아직 좀 남아 있음(업무 조회, 감사 기록). 모듈별로 BE API 로 옮기는 중.

## client 두 종류

| | 뭐냐 | 지금 |
|---|---|---|
| 🟡 **로그인 client** | 사람이 로그인해서 토큰 받는 앱 | `poc-backoffice` (FE), `tp-app` (앱) |
| 🟢 **이름표 client** | 로그인 안 함. 리소스 서버 이름. 토큰 `aud` 에 박히고 role 이 여기 붙음 | `backoffice-api` (두 realm 에 같은 이름) |

## 큰 틀 한 장

realm 은 사람 풀, 로그인 client 는 앱, 이름표 client 는 API. role 은 이름표 밑에, 사람은 그룹으로 묶어 role 을 받는다. 직원은 realm 하나(크로스허브·연맹·업체 다). 그래야 우리 직원이 한 번 로그인으로 두 백오피스를 다 들어가고, 제휴사 직원은 role 이 없어 제휴사 백오피스만 들어간다.

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 480, "nodeSpacing": 24, "rankSpacing": 36, "padding": 10}}}%%
flowchart TB
  subgraph KC["Keycloak (인증 서버)"]
    direction LR
    subgraph R1["realm crosshub · 직원"]
      direction TB
      G1["그룹: 크로스허브 · 연맹 · 업체"]
      L1a["partner-backoffice-fe"]
      L1b["tp-backoffice-fe (앞으로)"]
      N1a["partner-backoffice-api<br/>role: crosshub · affiliate · partner"]
      N1b["tp-backoffice-api (앞으로)<br/>role: tp-admin · tp-ops"]
      G1 --> N1a
      G1 --> N1b
    end
    subgraph R2["realm idblock · 고객"]
      direction TB
      L2a["tp-app"]
      L2b["외부 서비스 (앞으로)"]
      N2["tp-api (앞으로)<br/>role 없음 · sub = 본인"]
    end
  end

  subgraph RS["리소스 서버 (1대든 N대든)"]
    direction LR
    P["partner-backoffice-api"]
    O["tp-backoffice-api"]
    T["tp-api"]
    DB[("운영 DB")]
    P --- DB
    O --- DB
    T --- DB
  end

  N1a -. aud .-> P
  N1b -. aud .-> O
  N2 -. aud .-> T

  classDef app fill:#FDCB6E,stroke:#e1b12c,color:#2d3436
  classDef tag fill:#55efc4,stroke:#00B894,color:#2d3436
  classDef grp fill:#dfe6e9,stroke:#b2bec3,color:#2d3436
  classDef rs fill:#00B894,stroke:#019875,color:#fff
  classDef db fill:#636e72,stroke:#2d3436,color:#fff
  class L1a,L1b,L2a,L2b app
  class N1a,N1b,N2 tag
  class G1 grp
  class P,O,T rs
  class DB db
  style R1 fill:#f3f0ff,stroke:#6C5CE7
  style R2 fill:#f3f0ff,stroke:#6C5CE7
  style KC fill:#ffffff,stroke:#6C5CE7,stroke-width:2px
  style RS fill:#e8f8f3,stroke:#00B894
```

노랑 = 로그인 client (앱) · 연두 = 이름표 client (API, role 은 여기 밑에) · 회색 = 그룹 · 진초록 = 리소스 서버 · 점선 = aud

지금은 이름표가 `backoffice-api` 하나고 두 realm 에 같은 이름으로 있다. 위 그림은 그게 `partner-backoffice-api` 로 이름이 바뀌고 나머지 두 개가 더 생긴 모양.

직원 realm 을 안 나누는 이유: SSO 는 realm 안에서만 된다. 세션 길이·OTP·들어갈 수 있는 앱은 client 와 role 로 나뉘고, 못 나누는 건 비번 규칙과 잠금 정책뿐. 그게 달라져야 하면 그때 realm 을 가르고 브로커로 SSO 를 잇는다.

## BE 가 토큰에서 보는 순서

```mermaid
flowchart LR
  A["iss<br/>어느 realm 이 발급?"] --> B["aud<br/>우리 이름표 있어?"] --> C["role<br/>뭘 할 수 있어?"] --> D["범위<br/>어디까지?"]
  A1["crosshub = 직원<br/>idblock = 고객<br/>그 외 401"] -.- A
  B1["backoffice-api<br/>없으면 401"] -.- B
  C1["직원: crosshub / affiliate / partner<br/>고객: 없음 (ROLE_CUSTOMER 하나)"] -.- C
  D1["DB 가 정함<br/>직원: 업체 범위<br/>고객: 본인 것만 (sub)"] -.- D

  classDef step fill:#0984e3,stroke:#0652DD,color:#fff
  classDef note fill:#dfe6e9,stroke:#b2bec3,color:#2d3436
  class A,B,C,D step
  class A1,B1,C1,D1 note
```

`azp`(어느 앱이 받아간 토큰인지)는 안 봄. 앱이 늘 때마다 BE 고치기 싫어서.

## 토큰은 이렇게 생겼다

OIDC 로 로그인하면 토큰 세 개가 온다. BE 에 보내는 건 **access token** 하나. JWT 라 점 두 개로 나뉘고(헤더.본문.서명), 본문은 base64 를 풀면 그냥 JSON 이다. 암호화가 아니라 누구나 읽을 수 있고, 대신 서명 때문에 못 고친다.

| 토큰 | 누구 주는 거 | 어디 쓰나 |
|---|---|---|
| access_token | BE 에 | 매 요청 `Authorization: Bearer …`. 5분 |
| refresh_token | 인증 서버에 | access 만료되면 새로 받기. 직원 30분, 고객 30일 |
| id_token | 앱 자기한테 | "로그인한 사람 이름 보여주기". BE 에 안 보냄 |

**직원 access token 본문** (test-crosshub, crosshub realm)

```json
{
  "iss": "http://localhost:8080/realms/crosshub",   // ① 어느 realm 이 발급했나 → 직원
  "aud": "backoffice-api",                          // ② 우리 이름표. 없으면 BE 가 401
  "azp": "poc-backoffice",                          //    토큰을 받아간 앱. BE 는 안 봄
  "sub": "e4c288e3-…",                              //    Keycloak 사용자 id
  "preferred_username": "test-crosshub",            //    BE 가 User.loginId 로 찾는 키
  "resource_access": {
    "backoffice-api": { "roles": ["crosshub"] }    // ③ 이름표 밑에 붙은 role → ROLE_CROSSHUB
  },
  "name": "크로스허브 운영팀",
  "exp": 1792984000, "iat": 1792983700,             //    만료·발급 (5분)
  "sid": "lwZWtlZt…"                                //    SSO 세션. 백채널 로그아웃이 이걸로 FE 세션을 찾음
}
```

**고객 access token 본문** (test-customer, idblock realm)

```json
{
  "iss": "http://localhost:8080/realms/idblock",    // ① 고객 realm
  "aud": "backoffice-api",                          // ② 같은 이름표
  "azp": "tp-app",
  "sub": "15cdc824-284f-4b5c-99ac-1993b021079e",    // ④ 고객 ID. Booking.customerId 에 이 값이 들어감
  "email": "test-customer@example.com",
  "email_verified": true,
  "name": "테스트 고객",
  "scope": "openid email profile",
  "exp": 1792984300, "iat": 1792984000
  // resource_access 없음 → ③ role 없음. BE 가 ROLE_CUSTOMER 하나만 줌
}
```

두 토큰의 차이는 `iss` 하나와 role 유무뿐. BE 는 `iss` 로 직원/고객을 가르고, `aud` 로 우리 것인지 확인하고, 직원은 role · 고객은 `sub` 로 범위를 정한다.

## 앞으로

TP 앱이 진짜 생기면 고객은 잔고·결제까지 봄. TP 운영용 백오피스도 따로 생김. DB 는 하나로 갈지 서버를 나눌지 미정. **어느 쪽이든 리소스 서버마다 이름표 하나씩** 이면 끝.

```mermaid
flowchart LR
  subgraph KC["🔐 인증 서버"]
    direction TB
    R1["crosshub<br/>직원"]
    R2["idblock<br/>고객"]
  end
  subgraph CL[" "]
    direction TB
    PFE["제휴사 백오피스 FE"]
    TFE["TP 백오피스 FE (신규)"]
    APP["TP 앱"]
  end
  subgraph RS["🗄️ 리소스 서버 (1대든 N대든)"]
    direction TB
    PAPI["partner-backoffice-api<br/>제휴사 업무"]
    OAPI["tp-backoffice-api<br/>TP 운영"]
    TAPI["tp-api<br/>잔고·결제·예약"]
  end
  DB[("운영 DB")]

  R1 --> PFE --> PAPI
  R1 --> TFE --> OAPI
  R2 --> APP --> TAPI
  PAPI --- DB
  OAPI --- DB
  TAPI --- DB

  classDef auth fill:#6C5CE7,stroke:#4834d4,color:#fff
  classDef app fill:#FDCB6E,stroke:#e1b12c,color:#2d3436
  classDef rs fill:#00B894,stroke:#019875,color:#fff
  classDef db fill:#636e72,stroke:#2d3436,color:#fff
  class R1,R2 auth
  class PFE,TFE,APP app
  class PAPI,OAPI,TAPI rs
  class DB db
  style CL fill:#fff8e1,stroke:#e1b12c,stroke-dasharray:4
```

정해야 할 것
- 서버를 나눌지, 하나에 API 만 나눌지
- TP 백오피스 role 이름
- `backoffice-api` 를 `partner-backoffice-api` 로 바꿀지

## 한 것 / 할 것

| ✅ 한 것 | ⬜ 할 것 |
|---|---|
| 인증 서버 레포 분리 (`keycloak-auth`) | FE 남은 DB 직접 조회를 BE 로 |
| realm 2개 + 이름표 client | 소셜 로그인 |
| BE 가 두 realm 토큰 검증, 직원/고객 경로 분리 | 이메일(SMTP) |
| FE 세션 Redis, `/api/v1/me` | 운영 주소·HTTPS |
| 고객용 API (상품·예약·문의) | TP 백오피스·tp-api 설계 |
| 브라우저 데모 `demo/tp-web` | TP 앱 개발 |
