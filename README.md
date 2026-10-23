# poc-backoffice-BE

크로스허브 백오피스 백엔드 (Spring Boot, Java 17+, Gradle).
v1(Next.js 풀스택)에서 FE/BE를 분리하며 BE를 새로 세운 것. 첫 슬라이스는 **상품(products) 조회 API**.

## 전제
- v1(Prisma)이 쓰던 **Postgres를 그대로 재사용** (테이블은 Prisma 마이그레이션이 단일 진실, BE는 `ddl-auto=none`).
- 인증은 **Keycloak crosshub realm**. 토큰의 `resource_access.poc-backoffice.roles`(client role: `crosshub`/`affiliate`/`partner` — DB `User.role`과 같은 이름)를 1차 권한으로 매핑.
- **권한 판단 기준은 DB(`User.role`)**. 토큰 role은 "이 API를 부를 수 있나" 거름망이고, 업체 범위·crosshub 전용 여부는 `CallerService`가 DB로 최종 판단. 계정 발급 시 토큰 role과 DB role을 같이 세팅한다.
- 토큰은 서명·만료·issuer + `aud` 또는 `azp`가 `poc-backoffice`인지까지 검증 (다른 client 토큰 거부).

## 실행
```bash
cp .env.example .env    # DB·Keycloak 주소 채우기
./gradlew bootRun                                  # 기본: 토큰 검증 켜짐
SPRING_PROFILES_ACTIVE=secure ./gradlew bootRun    # .env에 dev가 켜져 있어도 강제로 검증
# → http://localhost:8081
```
로컬에서 토큰 없이 FE↔BE만 맞춰보려면 `.env`에 `APP_DEV_ENABLED=true` (기동 로그에 경고가 뜬다. 배포 금지).
JDK 17 이상 필요. 의존성 다운로드를 위해 인터넷 연결 필요(Maven Central).

## API (products)
| 메서드 | 경로 | 권한(토큰 role) | 업체 범위 | 설명 |
|---|---|---|---|---|
| GET | `/api/v1/products?status=` | crosshub/affiliate/partner | crosshub 전체, affiliate 대행 업체, partner 본인 업체 | 목록(요약) |
| GET | `/api/v1/products/{id}` | crosshub/affiliate/partner | 범위 밖이면 404 | 상세 |
| POST | `/api/v1/products` | crosshub/affiliate/partner | partner는 본인 업체로 강제, affiliate는 대행 업체만 | 생성(상태 review로 시작) |
| PUT | `/api/v1/products/{id}` | crosshub/affiliate/partner | 범위 안만 | 수정(보낸 필드만 반영) |

crosshub 전용: `/api/v1/accounts`, `/api/v1/affiliate-shares` (토큰 role + DB role 둘 다 확인).

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
