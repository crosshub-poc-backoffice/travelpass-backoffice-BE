-- 상품 사진·사업자등록증: 전체 URL 대신 저장소 안의 경로(key)만 저장한다. URL 은 BE 가 응답할 때 S3_PUBLIC_URL 을 붙여 만든다 (storage/StoredFiles).
-- 예) http://localhost:9000/product-images/products/abc.png → products/abc.png. 데모 시드의 /seed-images/… 는 그대로.
UPDATE "Product"
SET images = ARRAY(
    SELECT regexp_replace(x.i, '^https?://.+/(products/[^/]+)$', '\1')
    FROM unnest(images) WITH ORDINALITY AS x(i, n)
    ORDER BY x.n)
WHERE EXISTS (SELECT 1 FROM unnest(images) i WHERE i ~ '^https?://');

ALTER TABLE "Partner" RENAME COLUMN "businessLicenseUrl" TO "businessLicenseKey";
UPDATE "Partner"
SET "businessLicenseKey" = regexp_replace("businessLicenseKey", '^https?://.+/(partners/[^/]+)$', '\1')
WHERE "businessLicenseKey" ~ '^https?://';

-- 대기 중인 변경요청 본문(proposedData)의 사진 URL 은 그대로 둔다 — 승인할 때 ProductImages 가 같은 저장소 URL 을 key 로 바꾼다.
