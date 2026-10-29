package com.crosshub.backoffice.customer.dto;

import com.crosshub.backoffice.inquiry.Inquiry;
import com.crosshub.backoffice.inquiry.InquiryMessage;
import com.crosshub.backoffice.product.Product;
import java.time.OffsetDateTime;
import java.util.List;

/** 고객용 API 응답·요청. 직원용 DTO 와 달리 수수료·정산액 같은 내부 금액은 내보내지 않는다 */
public final class CustomerDtos {

    private CustomerDtos() {}

    /** 앱 상품 목록: 노출중(live) 상품만 */
    public record ProductSummary(String id, String name, String category, String partnerName, String currency,
                                 Integer priceFrom, String confirmType) {
        public static ProductSummary from(Product p) {
            return new ProductSummary(p.getId(), p.getName(), p.getCategory(),
                    p.getPartner() != null ? p.getPartner().getName() : null,
                    p.getPartner() != null ? p.getPartner().getCurrency() : null,
                    p.getPriceFrom(), p.getConfirmType());
        }
    }

    public record MessageView(String sender, String text, String textTranslated, OffsetDateTime createdAt) {
        public static MessageView from(InquiryMessage m) {
            return new MessageView(m.getSender(), m.getText(), m.getTextTranslated(), m.getCreatedAt());
        }
    }

    public record InquiryView(String id, String productId, String productName, String language, String question,
                              String answer, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                              List<MessageView> messages) {
        public static InquiryView from(Inquiry i, String productName) {
            return new InquiryView(i.getId(), i.getProductId(), productName, i.getLanguage(), i.getQuestion(),
                    i.getAnswer(), i.getStatus(), i.getCreatedAt(), i.getUpdatedAt(),
                    i.getMessages().stream().map(MessageView::from).toList());
        }
    }

    /** 문의 접수. language = 고객 앱 언어(en|zh|ja|vi) */
    public record CreateInquiry(String productId, String language, String question, String idempotencyKey) {}
}
