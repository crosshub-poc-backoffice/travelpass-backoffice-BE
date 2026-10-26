package com.crosshub.backoffice.product.change;

import com.fasterxml.jackson.databind.JsonNode;

/** 변경요청 요청/응답. proposedData는 FE ProductInput JSON 그대로 */
public final class ChangeRequestDtos {
    private ChangeRequestDtos() {}

    public record CreateRequest(String type, String targetStatus, JsonNode proposedData) {}

    public record RejectRequest(String reason) {}

    /** 요청 1건 + 대상 상품(이름·업체). FE가 승인 반영·알림에 쓴다 */
    public record ChangeRequestView(String id, String productId, String productName, String partnerId,
                                    String type, String targetStatus, String status, JsonNode proposedData) {}

    public record Created(String id) {}

    /** 목록 뱃지·상세 배너용 요청 1건 (FE ChangeRequestRow). proposedData 는 빠른 보기에서만(withProposed) */
    public record ChangeRequestRow(String id, String productId, String type, String targetStatus, String requestedByName,
                                   String rejectReason, String status, java.time.OffsetDateTime createdAt, JsonNode proposedData) {}

    /** 대시보드: 대기중 요청 한 줄 */
    public record PendingRow(String id, String productId, String productName, String partnerName, String type, String targetStatus,
                             java.time.OffsetDateTime createdAt) {}

    public record PendingPage(java.util.List<PendingRow> rows, long total) {}
}
