-- V1 기준선: FE(Prisma) 마이그레이션 56개(마지막 20261023130000_session_keycloak_sid)를 빈 DB에 적용한 뒤
-- pg_dump --schema-only로 뜬 구조. 이 파일부터 테이블 구조의 주인은 BE(Flyway)다. 고치지 않는다.
--  - 이미 Prisma로 만들어진 DB(로컬·Supabase)는 이 파일을 실행하지 않고 baseline(V1)으로 표시만 한다 (spring.flyway.baseline-on-migrate)
--  - 빈 DB는 이 파일로 만든다
--  - Prisma의 _prisma_migrations 테이블은 여기 없다 (옛 이력이라 기존 DB에만 남는다)
--
-- PostgreSQL database dump
--


-- Dumped from database version 17.11 (Debian 17.11-1.pgdg13+2)
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)


--
-- Name: BookingStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."BookingStatus" AS ENUM (
    'pending',
    'quoted',
    'confirmed',
    'rejected',
    'completed',
    'canceled',
    'refunded',
    'withdrawn'
);


--
-- Name: ConfirmType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ConfirmType" AS ENUM (
    'instant',
    'quote'
);


--
-- Name: CustomerDecision; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CustomerDecision" AS ENUM (
    'waiting',
    'accepted',
    'declined'
);


--
-- Name: InquiryLanguage; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."InquiryLanguage" AS ENUM (
    'en',
    'zh',
    'ja',
    'vi'
);


--
-- Name: InquiryStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."InquiryStatus" AS ENUM (
    'pending',
    'answered'
);


--
-- Name: LoginEvent; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."LoginEvent" AS ENUM (
    'login',
    'logout',
    'failed'
);


--
-- Name: MessageSender; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."MessageSender" AS ENUM (
    'customer',
    'admin'
);


--
-- Name: NotificationType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."NotificationType" AS ENUM (
    'booking_quote_responded',
    'product_status_changed',
    'product_change_requested',
    'product_change_resolved',
    'product_direct_change'
);


--
-- Name: PaymentType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PaymentType" AS ENUM (
    'payment',
    'refund'
);


--
-- Name: ProductCategory; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductCategory" AS ENUM (
    'tour',
    'lodging',
    'transport',
    'package',
    'other'
);


--
-- Name: ProductChangeRequestStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductChangeRequestStatus" AS ENUM (
    'pending',
    'approved',
    'rejected'
);


--
-- Name: ProductChangeType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductChangeType" AS ENUM (
    'edit',
    'delete',
    'hide'
);


--
-- Name: ProductStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductStatus" AS ENUM (
    'draft',
    'review',
    'live',
    'hidden'
);


--
-- Name: Role; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."Role" AS ENUM (
    'crosshub',
    'affiliate',
    'partner'
);


--
-- Name: SettlementBasis; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."SettlementBasis" AS ENUM (
    'completed',
    'paid'
);


--
-- Name: SettlementStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."SettlementStatus" AS ENUM (
    'pending',
    'paid'
);




--
-- Name: AccessLog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AccessLog" (
    id text NOT NULL,
    "userId" text NOT NULL,
    menu text NOT NULL,
    kind text NOT NULL,
    path text,
    ip text,
    "userAgent" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: Affiliate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Affiliate" (
    id text NOT NULL,
    name text NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    "shareRate" integer,
    "bankAccountHolder" text,
    "bankAccountNumber" text,
    "bankName" text
);


--
-- Name: AffiliateSettlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AffiliateSettlement" (
    id text NOT NULL,
    "affiliateId" text NOT NULL,
    period text NOT NULL,
    currency text NOT NULL,
    amount integer NOT NULL,
    "partnerCount" integer NOT NULL,
    status public."SettlementStatus" DEFAULT 'pending'::public."SettlementStatus" NOT NULL,
    "payDate" date,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL
);


--
-- Name: AuditLog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AuditLog" (
    id text NOT NULL,
    "userId" text NOT NULL,
    "partnerId" text,
    action text NOT NULL,
    "targetId" text,
    "targetName" text,
    detail jsonb,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: Booking; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Booking" (
    id text NOT NULL,
    "productId" text NOT NULL,
    "customerName" text NOT NULL,
    nationality text NOT NULL,
    "useDate" date NOT NULL,
    people integer NOT NULL,
    amount integer NOT NULL,
    status public."BookingStatus" DEFAULT 'pending'::public."BookingStatus" NOT NULL,
    "confirmType" public."ConfirmType" NOT NULL,
    "requestedAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "quoteAmount" integer,
    "quoteValidUntil" date,
    "quoteMessage" text,
    "customerDecision" public."CustomerDecision",
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    "idempotencyKey" text,
    phone text,
    email text,
    "priceBreakdown" jsonb,
    "cancelFee" integer,
    commission integer DEFAULT 0 NOT NULL,
    "commissionRate" integer DEFAULT 0 NOT NULL,
    net integer DEFAULT 0 NOT NULL,
    "affiliateShareRate" integer DEFAULT 0 NOT NULL,
    "pgFeeRate" double precision DEFAULT 0 NOT NULL,
    "cancelReason" text,
    "partnerId" text NOT NULL
);


--
-- Name: Inquiry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Inquiry" (
    id text NOT NULL,
    "productId" text NOT NULL,
    "customerName" text NOT NULL,
    language public."InquiryLanguage" NOT NULL,
    question text NOT NULL,
    "questionTranslated" text NOT NULL,
    answer text,
    "answerTranslated" text,
    status public."InquiryStatus" DEFAULT 'pending'::public."InquiryStatus" NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    "idempotencyKey" text,
    "questionTranslatedLocale" text
);


--
-- Name: InquiryMessage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."InquiryMessage" (
    id text NOT NULL,
    "inquiryId" text NOT NULL,
    sender public."MessageSender" NOT NULL,
    text text NOT NULL,
    "textTranslated" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "translatedLocale" text,
    "textLocale" text
);


--
-- Name: LiveNotificationDismissal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."LiveNotificationDismissal" (
    "userId" text NOT NULL,
    key text NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: LoginLog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."LoginLog" (
    id text NOT NULL,
    "userId" text,
    "loginId" text NOT NULL,
    event public."LoginEvent" NOT NULL,
    "failReason" text,
    ip text,
    "userAgent" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: Notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Notification" (
    id text NOT NULL,
    "userId" text NOT NULL,
    type public."NotificationType" NOT NULL,
    "sourceId" text NOT NULL,
    payload jsonb NOT NULL,
    read boolean DEFAULT false NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: Partner; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Partner" (
    id text NOT NULL,
    name text NOT NULL,
    "proxyAffiliateId" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    address text,
    "businessRegistrationNumber" text,
    "commissionRate" integer,
    email text,
    manager text,
    memo text,
    phone text,
    currency text DEFAULT 'JPY'::text NOT NULL,
    "bankAccountHolder" text,
    "bankAccountNumber" text,
    "bankName" text,
    "bankCode" text,
    "businessLicenseUrl" text,
    "corporateName" text,
    "representativeName" text,
    "subManager" text,
    "subManagerPhone" text
);


--
-- Name: Payment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Payment" (
    id text NOT NULL,
    "bookingId" text NOT NULL,
    type public."PaymentType" NOT NULL,
    amount integer NOT NULL,
    pg text NOT NULL,
    "pgTransactionId" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: PgSetting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PgSetting" (
    currency text NOT NULL,
    "pgName" text NOT NULL,
    "feeRate" double precision DEFAULT 0 NOT NULL,
    basis public."SettlementBasis" DEFAULT 'completed'::public."SettlementBasis" NOT NULL,
    "payDay" integer DEFAULT 15 NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL
);


--
-- Name: Product; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Product" (
    id text NOT NULL,
    "partnerId" text NOT NULL,
    status public."ProductStatus" DEFAULT 'review'::public."ProductStatus" NOT NULL,
    "confirmType" public."ConfirmType" DEFAULT 'instant'::public."ConfirmType" NOT NULL,
    name text NOT NULL,
    images text[] DEFAULT ARRAY[]::text[],
    description text DEFAULT ''::text NOT NULL,
    "saleStart" date,
    "saleEnd" date,
    "cancelRules" jsonb DEFAULT '[]'::jsonb NOT NULL,
    "priceFrom" integer,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    "deadlineDays" integer DEFAULT 1 NOT NULL,
    "deadlineTime" text DEFAULT '17:00'::text NOT NULL,
    schedule jsonb DEFAULT '{}'::jsonb NOT NULL,
    blocks jsonb DEFAULT '[]'::jsonb NOT NULL,
    "priceTable" jsonb NOT NULL,
    "hiddenByPartnerOff" boolean DEFAULT false NOT NULL,
    "reviewRejectReason" text,
    category public."ProductCategory" DEFAULT 'other'::public."ProductCategory" NOT NULL,
    "sourceLocale" text
);


--
-- Name: ProductChangeRequest; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ProductChangeRequest" (
    id text NOT NULL,
    "productId" text NOT NULL,
    type public."ProductChangeType" NOT NULL,
    "targetStatus" public."ProductStatus",
    "proposedData" jsonb,
    status public."ProductChangeRequestStatus" DEFAULT 'pending'::public."ProductChangeRequestStatus" NOT NULL,
    "requestedById" text NOT NULL,
    "resolvedById" text,
    "resolvedAt" timestamp with time zone,
    "rejectReason" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ProductTranslation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ProductTranslation" (
    id text NOT NULL,
    "productId" text NOT NULL,
    locale text NOT NULL,
    texts jsonb DEFAULT '{}'::jsonb NOT NULL,
    sources jsonb DEFAULT '{}'::jsonb NOT NULL,
    reviewed boolean DEFAULT false NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL
);


--
-- Name: Session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Session" (
    id text NOT NULL,
    "userId" text NOT NULL,
    "activePartnerId" text,
    "expiresAt" timestamp with time zone NOT NULL,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "kcAccessToken" text,
    "kcAccessExpiresAt" timestamp with time zone,
    "kcRefreshToken" text,
    "kcIdToken" text,
    "kcSid" text,
    "kcSub" text
);


--
-- Name: Settlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Settlement" (
    id text NOT NULL,
    "partnerId" text NOT NULL,
    period text NOT NULL,
    gross integer NOT NULL,
    "commissionRate" integer NOT NULL,
    commission integer NOT NULL,
    net integer NOT NULL,
    "bookingCount" integer NOT NULL,
    status public."SettlementStatus" DEFAULT 'pending'::public."SettlementStatus" NOT NULL,
    "payDate" date,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL
);


--
-- Name: User; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."User" (
    id text NOT NULL,
    "loginId" text NOT NULL,
    name text NOT NULL,
    role public."Role" NOT NULL,
    "affiliateId" text,
    "partnerId" text,
    "createdAt" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp with time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    "lastLoginAt" timestamp with time zone,
    CONSTRAINT "User_role_org_check" CHECK ((((role = 'crosshub'::public."Role") AND ("affiliateId" IS NULL) AND ("partnerId" IS NULL)) OR ((role = 'affiliate'::public."Role") AND ("affiliateId" IS NOT NULL) AND ("partnerId" IS NULL)) OR ((role = 'partner'::public."Role") AND ("partnerId" IS NOT NULL) AND ("affiliateId" IS NULL))))
);


--
-- Name: AccessLog AccessLog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AccessLog"
    ADD CONSTRAINT "AccessLog_pkey" PRIMARY KEY (id);


--
-- Name: AffiliateSettlement AffiliateSettlement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AffiliateSettlement"
    ADD CONSTRAINT "AffiliateSettlement_pkey" PRIMARY KEY (id);


--
-- Name: Affiliate Affiliate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Affiliate"
    ADD CONSTRAINT "Affiliate_pkey" PRIMARY KEY (id);


--
-- Name: AuditLog AuditLog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AuditLog"
    ADD CONSTRAINT "AuditLog_pkey" PRIMARY KEY (id);


--
-- Name: Booking Booking_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Booking"
    ADD CONSTRAINT "Booking_pkey" PRIMARY KEY (id);


--
-- Name: InquiryMessage InquiryMessage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."InquiryMessage"
    ADD CONSTRAINT "InquiryMessage_pkey" PRIMARY KEY (id);


--
-- Name: Inquiry Inquiry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Inquiry"
    ADD CONSTRAINT "Inquiry_pkey" PRIMARY KEY (id);


--
-- Name: LiveNotificationDismissal LiveNotificationDismissal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."LiveNotificationDismissal"
    ADD CONSTRAINT "LiveNotificationDismissal_pkey" PRIMARY KEY ("userId", key);


--
-- Name: LoginLog LoginLog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."LoginLog"
    ADD CONSTRAINT "LoginLog_pkey" PRIMARY KEY (id);


--
-- Name: Notification Notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Notification"
    ADD CONSTRAINT "Notification_pkey" PRIMARY KEY (id);


--
-- Name: Partner Partner_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Partner"
    ADD CONSTRAINT "Partner_pkey" PRIMARY KEY (id);


--
-- Name: Payment Payment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Payment"
    ADD CONSTRAINT "Payment_pkey" PRIMARY KEY (id);


--
-- Name: PgSetting PgSetting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PgSetting"
    ADD CONSTRAINT "PgSetting_pkey" PRIMARY KEY (currency);


--
-- Name: ProductChangeRequest ProductChangeRequest_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductChangeRequest"
    ADD CONSTRAINT "ProductChangeRequest_pkey" PRIMARY KEY (id);


--
-- Name: ProductTranslation ProductTranslation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductTranslation"
    ADD CONSTRAINT "ProductTranslation_pkey" PRIMARY KEY (id);


--
-- Name: Product Product_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Product"
    ADD CONSTRAINT "Product_pkey" PRIMARY KEY (id);


--
-- Name: Session Session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Session"
    ADD CONSTRAINT "Session_pkey" PRIMARY KEY (id);


--
-- Name: Settlement Settlement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Settlement"
    ADD CONSTRAINT "Settlement_pkey" PRIMARY KEY (id);


--
-- Name: User User_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT "User_pkey" PRIMARY KEY (id);


--
-- Name: AccessLog_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "AccessLog_createdAt_idx" ON public."AccessLog" USING btree ("createdAt");


--
-- Name: AffiliateSettlement_affiliateId_period_currency_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "AffiliateSettlement_affiliateId_period_currency_key" ON public."AffiliateSettlement" USING btree ("affiliateId", period, currency);


--
-- Name: AuditLog_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "AuditLog_createdAt_idx" ON public."AuditLog" USING btree ("createdAt");


--
-- Name: AuditLog_partnerId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "AuditLog_partnerId_createdAt_idx" ON public."AuditLog" USING btree ("partnerId", "createdAt");


--
-- Name: Booking_idempotencyKey_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "Booking_idempotencyKey_key" ON public."Booking" USING btree ("idempotencyKey");


--
-- Name: Booking_partnerId_status_useDate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Booking_partnerId_status_useDate_idx" ON public."Booking" USING btree ("partnerId", status, "useDate");


--
-- Name: Booking_productId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Booking_productId_idx" ON public."Booking" USING btree ("productId");


--
-- Name: Booking_requestedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Booking_requestedAt_idx" ON public."Booking" USING btree ("requestedAt");


--
-- Name: Booking_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Booking_status_idx" ON public."Booking" USING btree (status);


--
-- Name: Booking_useDate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Booking_useDate_idx" ON public."Booking" USING btree ("useDate");


--
-- Name: InquiryMessage_inquiryId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "InquiryMessage_inquiryId_idx" ON public."InquiryMessage" USING btree ("inquiryId");


--
-- Name: Inquiry_idempotencyKey_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "Inquiry_idempotencyKey_key" ON public."Inquiry" USING btree ("idempotencyKey");


--
-- Name: Inquiry_productId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Inquiry_productId_idx" ON public."Inquiry" USING btree ("productId");


--
-- Name: Inquiry_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Inquiry_status_idx" ON public."Inquiry" USING btree (status);


--
-- Name: LoginLog_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "LoginLog_createdAt_idx" ON public."LoginLog" USING btree ("createdAt");


--
-- Name: LoginLog_ip_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "LoginLog_ip_createdAt_idx" ON public."LoginLog" USING btree (ip, "createdAt");


--
-- Name: LoginLog_loginId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "LoginLog_loginId_createdAt_idx" ON public."LoginLog" USING btree ("loginId", "createdAt");


--
-- Name: LoginLog_userId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "LoginLog_userId_createdAt_idx" ON public."LoginLog" USING btree ("userId", "createdAt");


--
-- Name: Notification_userId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Notification_userId_createdAt_idx" ON public."Notification" USING btree ("userId", "createdAt");


--
-- Name: Notification_userId_read_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Notification_userId_read_idx" ON public."Notification" USING btree ("userId", read);


--
-- Name: Notification_userId_type_sourceId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "Notification_userId_type_sourceId_key" ON public."Notification" USING btree ("userId", type, "sourceId");


--
-- Name: Partner_proxyAffiliateId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Partner_proxyAffiliateId_idx" ON public."Partner" USING btree ("proxyAffiliateId");


--
-- Name: Payment_bookingId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Payment_bookingId_idx" ON public."Payment" USING btree ("bookingId");


--
-- Name: Payment_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Payment_createdAt_idx" ON public."Payment" USING btree ("createdAt");


--
-- Name: ProductChangeRequest_productId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ProductChangeRequest_productId_idx" ON public."ProductChangeRequest" USING btree ("productId");


--
-- Name: ProductChangeRequest_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ProductChangeRequest_status_idx" ON public."ProductChangeRequest" USING btree (status);


--
-- Name: ProductTranslation_productId_locale_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ProductTranslation_productId_locale_key" ON public."ProductTranslation" USING btree ("productId", locale);


--
-- Name: Product_partnerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Product_partnerId_idx" ON public."Product" USING btree ("partnerId");


--
-- Name: Product_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Product_status_idx" ON public."Product" USING btree (status);


--
-- Name: Session_kcSid_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Session_kcSid_idx" ON public."Session" USING btree ("kcSid");


--
-- Name: Session_kcSub_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Session_kcSub_idx" ON public."Session" USING btree ("kcSub");


--
-- Name: Session_userId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Session_userId_idx" ON public."Session" USING btree ("userId");


--
-- Name: Settlement_partnerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Settlement_partnerId_idx" ON public."Settlement" USING btree ("partnerId");


--
-- Name: Settlement_partnerId_period_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "Settlement_partnerId_period_key" ON public."Settlement" USING btree ("partnerId", period);


--
-- Name: User_affiliateId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "User_affiliateId_idx" ON public."User" USING btree ("affiliateId");


--
-- Name: User_loginId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "User_loginId_key" ON public."User" USING btree ("loginId");


--
-- Name: User_partnerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "User_partnerId_idx" ON public."User" USING btree ("partnerId");


--
-- Name: AccessLog AccessLog_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AccessLog"
    ADD CONSTRAINT "AccessLog_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: AffiliateSettlement AffiliateSettlement_affiliateId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AffiliateSettlement"
    ADD CONSTRAINT "AffiliateSettlement_affiliateId_fkey" FOREIGN KEY ("affiliateId") REFERENCES public."Affiliate"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: AuditLog AuditLog_partnerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AuditLog"
    ADD CONSTRAINT "AuditLog_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES public."Partner"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: AuditLog AuditLog_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AuditLog"
    ADD CONSTRAINT "AuditLog_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: Booking Booking_partnerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Booking"
    ADD CONSTRAINT "Booking_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES public."Partner"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: Booking Booking_productId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Booking"
    ADD CONSTRAINT "Booking_productId_fkey" FOREIGN KEY ("productId") REFERENCES public."Product"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: InquiryMessage InquiryMessage_inquiryId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."InquiryMessage"
    ADD CONSTRAINT "InquiryMessage_inquiryId_fkey" FOREIGN KEY ("inquiryId") REFERENCES public."Inquiry"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: Inquiry Inquiry_productId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Inquiry"
    ADD CONSTRAINT "Inquiry_productId_fkey" FOREIGN KEY ("productId") REFERENCES public."Product"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: LiveNotificationDismissal LiveNotificationDismissal_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."LiveNotificationDismissal"
    ADD CONSTRAINT "LiveNotificationDismissal_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: LoginLog LoginLog_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."LoginLog"
    ADD CONSTRAINT "LoginLog_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: Notification Notification_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Notification"
    ADD CONSTRAINT "Notification_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: Partner Partner_proxyAffiliateId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Partner"
    ADD CONSTRAINT "Partner_proxyAffiliateId_fkey" FOREIGN KEY ("proxyAffiliateId") REFERENCES public."Affiliate"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: Payment Payment_bookingId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Payment"
    ADD CONSTRAINT "Payment_bookingId_fkey" FOREIGN KEY ("bookingId") REFERENCES public."Booking"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: ProductChangeRequest ProductChangeRequest_productId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductChangeRequest"
    ADD CONSTRAINT "ProductChangeRequest_productId_fkey" FOREIGN KEY ("productId") REFERENCES public."Product"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ProductChangeRequest ProductChangeRequest_requestedById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductChangeRequest"
    ADD CONSTRAINT "ProductChangeRequest_requestedById_fkey" FOREIGN KEY ("requestedById") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: ProductChangeRequest ProductChangeRequest_resolvedById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductChangeRequest"
    ADD CONSTRAINT "ProductChangeRequest_resolvedById_fkey" FOREIGN KEY ("resolvedById") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: ProductTranslation ProductTranslation_productId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ProductTranslation"
    ADD CONSTRAINT "ProductTranslation_productId_fkey" FOREIGN KEY ("productId") REFERENCES public."Product"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: Product Product_partnerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Product"
    ADD CONSTRAINT "Product_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES public."Partner"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: Session Session_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Session"
    ADD CONSTRAINT "Session_userId_fkey" FOREIGN KEY ("userId") REFERENCES public."User"(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: Settlement Settlement_partnerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Settlement"
    ADD CONSTRAINT "Settlement_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES public."Partner"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: User User_affiliateId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT "User_affiliateId_fkey" FOREIGN KEY ("affiliateId") REFERENCES public."Affiliate"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: User User_partnerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT "User_partnerId_fkey" FOREIGN KEY ("partnerId") REFERENCES public."Partner"(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--


