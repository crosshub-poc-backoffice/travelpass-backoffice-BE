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
}
