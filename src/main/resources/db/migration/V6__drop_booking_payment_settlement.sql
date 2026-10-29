-- PoC 범위에서 예약·결제·정산을 뺐다 (2026-10-29). 플랫폼은 투어 정보 게재 + 업체 문의까지만.
-- 그 기능의 테이블·enum·컬럼과, 그 기능이 남긴 알림·작업 이력·접근 기록을 지운다. 되돌릴 수 없다.

-- 예약·결제·정산 테이블 (FK 때문에 결제 → 예약 순서)
DROP TABLE IF EXISTS "Payment";
DROP TABLE IF EXISTS "Booking";
DROP TABLE IF EXISTS "Settlement";
DROP TABLE IF EXISTS "AffiliateSettlement";
DROP TABLE IF EXISTS "PgSetting";

DROP TYPE IF EXISTS "BookingStatus";
DROP TYPE IF EXISTS "CustomerDecision";
DROP TYPE IF EXISTS "PaymentType";
DROP TYPE IF EXISTS "SettlementBasis";
DROP TYPE IF EXISTS "SettlementStatus";

-- 수수료율·정산 계좌·연맹 몫
ALTER TABLE "Partner"
    DROP COLUMN IF EXISTS "commissionRate",
    DROP COLUMN IF EXISTS "bankName",
    DROP COLUMN IF EXISTS "bankAccountNumber",
    DROP COLUMN IF EXISTS "bankAccountHolder",
    DROP COLUMN IF EXISTS "bankCode";
ALTER TABLE "Affiliate"
    DROP COLUMN IF EXISTS "shareRate",
    DROP COLUMN IF EXISTS "bankName",
    DROP COLUMN IF EXISTS "bankAccountNumber",
    DROP COLUMN IF EXISTS "bankAccountHolder";

-- 예약 알림: 저장형(견적 응답)과 라이브 알림 확인표시(booking-<id>)
DELETE FROM "Notification" WHERE type = 'booking_quote_responded';
DELETE FROM "LiveNotificationDismissal" WHERE key LIKE 'booking-%';
ALTER TYPE "NotificationType" RENAME TO "NotificationType_old";
CREATE TYPE "NotificationType" AS ENUM (
    'product_status_changed',
    'product_change_requested',
    'product_change_resolved',
    'product_direct_change'
);
ALTER TABLE "Notification" ALTER COLUMN type TYPE "NotificationType" USING type::text::"NotificationType";
DROP TYPE "NotificationType_old";

-- 없어진 기능의 작업 이력·접근 기록
DELETE FROM "AuditLog"
WHERE action LIKE 'booking\_%' OR action LIKE 'settlement\_%' OR action IN ('partner_commission', 'partner_bank');
DELETE FROM "AccessLog" WHERE menu IN ('bookings', 'settlements', 'stats', 'myAffiliate');
