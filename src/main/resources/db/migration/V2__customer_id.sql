-- 고객(idblock realm)과 예약·문의 연결. customerId = 고객 access token 의 sub (Keycloak user id, UUID).
-- 고객 예약은 로그인 필수(docs/auth-architecture.md)라 고객용 API 로 만든 행은 항상 채워진다.
-- 기존 행(옛 앱 API·시드로 들어온 것)은 NULL 로 남고, 백오피스 화면에는 영향이 없다.
ALTER TABLE "Booking" ADD COLUMN "customerId" text;
CREATE INDEX "Booking_customerId_idx" ON "Booking" ("customerId");
ALTER TABLE "Inquiry" ADD COLUMN "customerId" text;
CREATE INDEX "Inquiry_customerId_idx" ON "Inquiry" ("customerId");

-- FE 로그인 세션은 2026-10-26 에 FE 전용 Redis 로 옮겼다. 더는 아무도 읽고 쓰지 않는 테이블.
DROP TABLE IF EXISTS "Session";
