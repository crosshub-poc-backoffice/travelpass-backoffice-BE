-- 상품 검수 기록. FE 의 Prisma 스키마에는 ProductInspection 모델이 있었지만 마이그레이션이 없어 DB 에는 테이블이 없었다
-- (FE 의 검수 저장은 조용히 실패하고 있었다). 검수를 BE 로 옮기면서 테이블을 여기서 만든다.
-- status: done | failed, verdict: pass | warn | review, findings: 지적 목록 JSON (code·severity·source·field·evidence)
CREATE TABLE "ProductInspection" (
    id text NOT NULL,
    "productId" text NOT NULL,
    "changeRequestId" text,
    status text NOT NULL,
    verdict text NOT NULL,
    "aiRan" boolean DEFAULT false NOT NULL,
    findings jsonb DEFAULT '[]'::jsonb NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "ProductInspection_pkey" PRIMARY KEY (id),
    CONSTRAINT "ProductInspection_productId_fkey" FOREIGN KEY ("productId") REFERENCES "Product"(id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX "ProductInspection_productId_createdAt_idx" ON "ProductInspection" ("productId", "createdAt");
