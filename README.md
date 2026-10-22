# poc-backoffice-BE

크로스허브 백오피스 백엔드 (Spring Boot, Java 17+, Gradle).
v1(Next.js 풀스택)에서 FE/BE를 분리하며 BE를 새로 세운 것. 첫 슬라이스는 **상품(products) 조회 API**.

## 전제
- v1(Prisma)이 쓰던 **Postgres를 그대로 재사용** (테이블은 Prisma 마이그레이션이 단일 진실, BE는 `ddl-auto=none`).
- 인증은 **Keycloak crosshub realm**. 토큰의 `resource_access.poc-backoffice.roles`(client role: partner/ops/admin)를 권한으로 매핑.

## 실행
```bash
cp .env.example .env    # DB·Keycloak 주소 채우기
./gradlew bootRun
# → http://localhost:8081
```
JDK 17 이상 필요. 의존성 다운로드를 위해 인터넷 연결 필요(Maven Central).

## API (products)
| 메서드 | 경로 | 권한(토큰 role) | 업체 범위 | 설명 |
|---|---|---|---|---|
| GET | `/api/v1/products?status=` | partner/ops/admin | partner는 본인 업체만 자동 | 목록(요약) |
| GET | `/api/v1/products/{id}` | partner/ops/admin | 소유 업체만(아니면 404) | 상세 |
| POST | `/api/v1/products` | partner/admin | partner는 본인 업체로 강제 | 생성(상태 review로 시작) |
| PUT | `/api/v1/products/{id}` | partner/admin | 소유 업체만 | 수정(보낸 필드만 반영) |

모든 요청에 `Authorization: Bearer <access_token>` 필요.

**업체 범위는 어떻게 아나**: 토큰의 `preferred_username`(= Keycloak username)을 기존 `User` 테이블의
`loginId`로 찾아 그 사람의 `partnerId`/`role`을 얻는다(= "sub → 서비스 DB" 브리지). role=crosshub는 전체 접근,
role=partner는 자기 업체만. → Keycloak username과 User.loginId를 같은 값으로 발급하는 것이 전제.

## 다음 단계
- products 생성/수정(POST/PUT) + `sub`로 업체 범위 체크 (서비스 DB의 company_member)
- bookings / inquiries / inspect 도메인 이관
- FE(`poc-backoffice-FE`)의 데이터 접근을 이 API로 repoint (현재 `_moved-to-BE/`에 옛 Next API가 보관됨)

## 설계 메모
- enum 컬럼(status/confirmType/category)은 Postgres native enum이나 `package`가 Java 예약어라 **String 매핑 + `stringtype=unspecified`**.
- jsonb 컬럼(schedule/cancelRules/priceTable/blocks)은 원본 JSON 문자열 보존(@JsonRawValue로 그대로 응답).
- 포트 8081 (FE 3000 / Keycloak 8180과 분리).
