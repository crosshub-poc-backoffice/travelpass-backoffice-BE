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

  R1 == "① 로그인 → 토큰" ==> FE
  R2 == "① 로그인 → 토큰" ==> APP
  FE == "② 토큰 들고 호출" ==> BE
  APP == "② 토큰 들고 호출" ==> BE
  FE --- REDIS
  BE --- DB
  BE -. "③ 공개키로 검증만" .-> KC

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
- FE 로그인 세션은 FE 전용 Redis. FE 가 DB 를 직접 읽는 데가 아직 좀 남아 있음(업무 조회, 감사 기록). 모듈별로 BE API 로 옮기는 중.

## client 두 종류

| | 뭐냐 | 지금 |
|---|---|---|
| 🟡 **로그인 client** | 사람이 로그인해서 토큰 받는 앱 | `poc-backoffice` (FE), `tp-app` (앱) |
| 🟢 **이름표 client** | 로그인 안 함. 리소스 서버 이름. 토큰 `aud` 에 박히고 role 이 여기 붙음 | `backoffice-api` (두 realm 에 같은 이름) |

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
