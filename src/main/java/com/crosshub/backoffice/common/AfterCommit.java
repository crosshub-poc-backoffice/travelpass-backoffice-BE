package com.crosshub.backoffice.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에 실행한다. 트랜잭션 밖이면 바로 실행.
 *
 * 상품 저장 직후 백그라운드 번역·검수를 걸 때 쓴다 — 커밋 전에 다른 스레드가 돌면 아직 안 보이는 행을 읽거나
 * (새 상품) 옛 값을 읽는다(수정). 롤백되면 실행하지 않는다.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable job) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            job.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                job.run();
            }
        });
    }
}
