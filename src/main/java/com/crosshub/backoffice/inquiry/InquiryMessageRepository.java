package com.crosshub.backoffice.inquiry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryMessageRepository extends JpaRepository<InquiryMessage, String> {
    java.util.Optional<InquiryMessage> findFirstByInquiryIdAndSenderOrderByCreatedAtDesc(String inquiryId, String sender);
}
