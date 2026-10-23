-- Flyway가 migrate 할 때마다(BE 기동마다) 먼저 실행하는 확인 (Flyway SQL callback).
-- 예전 Prisma로 만든 DB인데 Prisma 마이그레이션이 덜 적용돼 있으면 BE 기동을 멈춘다 —
-- V1은 "Prisma 56개가 다 적용된 구조"라서, 덜 적용된 DB 위에 V2 이상을 올리면 빠진 구조가 영영 안 생긴다.
-- (첫 기동 때 baseline 표시는 이 확인보다 먼저 남지만, 이 확인을 통과하기 전엔 V2 이상이 실행되지 않는다)
-- 해결: FE 폴더에서 한 번만 `npx prisma migrate deploy` 실행 후 BE를 다시 띄운다.
DO $$
BEGIN
  -- _prisma_migrations가 없는 DB(빈 DB, Flyway로 만든 DB)는 확인할 것이 없다.
  -- 테이블이 없을 때 문장 해석부터 실패하지 않게 EXECUTE로 돌린다
  IF to_regclass('public._prisma_migrations') IS NULL THEN
    RETURN;
  END IF;
  EXECUTE $q$
    DO $inner$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM public._prisma_migrations
        WHERE migration_name = '20261023130000_session_keycloak_sid'
          AND finished_at IS NOT NULL AND rolled_back_at IS NULL
      ) THEN
        RAISE EXCEPTION 'Prisma 마이그레이션이 덜 적용된 DB입니다 (20261023130000_session_keycloak_sid 없음). FE 폴더에서 npx prisma migrate deploy를 한 번 실행한 뒤 BE를 다시 띄우세요. docs/db-ownership-plan.md 참고';
      END IF;
    END $inner$
  $q$;
END $$;
