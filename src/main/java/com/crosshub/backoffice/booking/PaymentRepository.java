package com.crosshub.backoffice.booking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findByBookingId(String bookingId);
}
