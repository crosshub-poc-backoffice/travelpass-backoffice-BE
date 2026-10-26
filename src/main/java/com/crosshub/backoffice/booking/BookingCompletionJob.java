package com.crosshub.backoffice.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일 04:00 JST: 이용일이 지난 확정 예약을 이용완료로 자동 전환한다 — 관리자가 매번 안 눌러도 정산·통계가
 * 이용 완료 건 기준으로 정확히 집계되게 한다. FE vercel.json 의 cron /api/cron/bookings-complete(19:00 UTC) 을 옮긴 것.
 * 이용일(useDate)은 날짜만 저장되므로 "오늘(JST)보다 앞선 날"이 지난 것이다.
 */
@Component
public class BookingCompletionJob {

    private static final Logger log = LoggerFactory.getLogger(BookingCompletionJob.class);

    private final BookingRepository bookings;

    public BookingCompletionJob(BookingRepository bookings) {
        this.bookings = bookings;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @Transactional
    public void run() {
        int completed = bookings.completePastConfirmed(Fees.jstToday());
        log.info("[bookings-complete] 이용완료 전환 {}건", completed);
    }
}
