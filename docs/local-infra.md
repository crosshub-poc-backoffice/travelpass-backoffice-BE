# 로컬 인프라 한눈에 (Docker)

2026-10-26 정리. 레포 세 개가 각자 자기 것만 띄운다 — 인증 서버는 Keycloak, 리소스 서버(BE)는 업무 DB·사진 저장소,
FE 는 세션 저장소. compose 파일은 레포당 하나다. BE 는 `Dockerfile` 도 있어 `docker compose --profile app up -d --build` 로 앱까지 컨테이너로 띄울 수 있다(2026-10-27, 아니면 `bootRun`).

| 레포 | compose 파일 | 프로젝트 이름 | 컨테이너 | 포트 | 볼륨 | 켜는 사람 |
|---|---|---|---|---|---|---|
| keycloak-auth | `docker-compose.yml` | `keycloak` | Keycloak 26.8.1, Postgres 16 | 8080 (관리 9000은 컨테이너 안) | `keycloak_keycloak-db` | 직접: `docker compose up -d --wait` (처음 한 번. Docker Desktop 과 같이 뜸) |
| travelpass-backoffice-BE | `compose.yaml` | `travelpass-backoffice` | Postgres 17 (업무 DB), RustFS (S3 호환 사진 저장소) | 5433, 9000/9001 | `travelpass-backoffice_db-data`, `_storage-data` | BE `./gradlew bootRun` 이 켠다 (spring-boot-docker-compose) |
| travelpass-backoffice-FE | `infra/redis/docker-compose.yml` | `travelpass-backoffice-fe` | Redis 7 (로그인 세션) | 6379 | `travelpass-backoffice-fe_redis-data` | FE `npm run dev` 가 켠다 (REDIS_URL 이 로컬일 때) |

프로젝트 이름은 전부 `name:` 으로 고정돼 있다. 폴더를 옮기거나 git worktree 로 받아도 같은 컨테이너·볼륨을 쓰기 위해서다. **바꾸지 말 것** — 바꾸면 데이터가 새 볼륨에서 시작된다.

## 처음 띄우는 순서

1. `keycloak-auth`: `cp .env.example .env` → 값 채우기 → `docker compose up -d --wait`
2. BE: `cp .env.example .env` → `./gradlew bootRun` (또는 `docker compose --profile app up -d --build`) (업무 DB·저장소 컨테이너가 같이 뜨고 Flyway 가 테이블을 만든다)
3. 데모 데이터(빈 DB일 때 한 번): `cd demo && npm install && npm run seed`
4. FE: `npm run dev` (Redis 컨테이너가 같이 뜬다. FE 는 DB 에 붙지 않는다)
5. 로그인: http://localhost:3000 → Keycloak 화면 → 테스트 직원 계정 (비번은 keycloak-auth `scripts/reset-password.sh`)

BE 는 Keycloak 이 아직 안 떠 있어도 기동된다 (첫 토큰 검증 때 공개키를 받아온다). FE 는 BE 가 떠 있어야 로그인이 된다 (`/api/v1/me`).

## 한 번에 보기·끄기

```bash
docker compose ls                               # 떠 있는 프로젝트
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
```
끄기는 각 레포에서 `docker compose down` (볼륨 유지) / `down -v` (데이터 삭제). Keycloak 을 `-v` 로 지우면 realm 이 JSON 에서 다시 import 되고 사용자 비번은 다시 넣어야 한다.

## 운영에서는

- Keycloak: 별도 호스트에 이 compose(또는 같은 설정)로. `start-dev` → `start`, `KC_HOSTNAME`, HTTPS (keycloak-auth README)
- BE DB: Supabase Postgres (Flyway 가 그대로 적용). 사진: Supabase Storage / S3
- FE Redis: Upstash (`REDIS_URL=rediss://...`). 로컬 compose 는 안 쓴다

## 치운 것 / 치울 것

- 옛 v1 레포 `travelpass-backoffice/docker-compose.yml` 은 BE `compose.yaml` 로 옮겨 온 것의 원본. v1 을 더 안 띄우면 지워도 된다 (프로젝트 이름이 같아 둘 다 띄우면 같은 볼륨을 두 컨테이너가 쓴다 — 동시에 띄우지 말 것).
- 작업 폴더의 `도커명령어.txt` (Keycloak 을 `docker run ... start-dev` 로 띄우던 명령) 은 keycloak-auth compose 로 대체됐다.
