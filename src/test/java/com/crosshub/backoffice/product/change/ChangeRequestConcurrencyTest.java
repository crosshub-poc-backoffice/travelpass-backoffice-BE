package com.crosshub.backoffice.product.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.TestEntities;
import com.crosshub.backoffice.TestServices;
import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.common.ApiExceptionHandler;
import com.crosshub.backoffice.notification.Notifier;
import com.crosshub.backoffice.product.Product;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.crosshub.backoffice.product.ProductService;
import com.crosshub.backoffice.product.ProductService.Origin;
import com.crosshub.backoffice.product.dto.ProductUpsertRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 변경요청 선점(조건부 UPDATE)·승인 반영·알림, 공통 에러 응답 형식 */
class ChangeRequestConcurrencyTest {

    static final Caller ADMIN = new Caller("u-admin", "test-crosshub", "crosshub", null, null, List.of());

    ProductChangeRequestRepository requests = mock(ProductChangeRequestRepository.class);
    ProductRepository products = mock(ProductRepository.class);
    ProductService productService = mock(ProductService.class);
    Notifier notifier = mock(Notifier.class);
    ChangeRequestService service = TestServices.changeRequestService(requests, products, productService, notifier);

    @Test
    void 이미_누가_처리했으면_승인은_404() {
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "hide", "hidden", null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markApproved(eq(r.getId()), eq("u-admin"), any())).thenReturn(0); // 경합에서 짐

        Throwable e = catchThrowable(() -> service.approve(ADMIN, r.getId()));
        assertThat(((ProductException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(productService, never()).setStatus(any(), any(), any(), any());
    }

    @Test
    void 승인하면_같은_트랜잭션에서_반영하고_소유측에_알린다() {
        Product p = TestEntities.product("p1", "pA", "live");
        when(products.findById("p1")).thenReturn(Optional.of(p));
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "hide", "hidden", null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markApproved(eq(r.getId()), eq("u-admin"), any())).thenReturn(1);
        when(notifier.partnerStakeholders("pA", false, true, true)).thenReturn(List.of("u-partner", "u-aff"));

        service.approve(ADMIN, r.getId());

        verify(productService).setStatus(eq(ADMIN), eq("p1"), any(), eq(Origin.VIA_REQUEST));
        verify(notifier).notify(eq(List.of("u-partner", "u-aff")), eq("product_change_resolved"), eq(r.getId() + ":approved"), any());
    }

    @Test
    void 반영이_실패하면_예외가_그대로_올라가고_알림은_없다() {
        Product p = TestEntities.product("p1", "pA", "live");
        when(products.findById("p1")).thenReturn(Optional.of(p));
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "delete", null, null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markApproved(eq(r.getId()), eq("u-admin"), any())).thenReturn(1);
        doThrow(new ProductException(HttpStatus.CONFLICT, "hasInquiries")).when(productService).delete(ADMIN, "p1", Origin.VIA_REQUEST);

        Throwable e = catchThrowable(() -> service.approve(ADMIN, r.getId()));
        assertThat(((ProductException) e).code()).isEqualTo("hasInquiries"); // 트랜잭션이 롤백돼 승인 표시도 사라진다
        verify(notifier, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 수정요청_본문에서_판매기간과_대표가를_계산한다() {
        String proposed = """
            {"partnerId":"pA","category":"tour","name":"바뀜","images":[],"description":"d","deadlineDays":1,"deadlineTime":"17:00",
             "confirmType":"instant","schedule":{"base":{"start":"2026-06-01","end":"2026-06-03","weekdays":[0,1,2,3,4,5,6],"times":[],"slotPeople":{},"minPeople":null,"maxPeople":null},"exceptions":[]},
             "cancelRules":[],"priceTable":{"categories":[{"id":"a","name":"성인"}],"plans":[{"id":"p","name":"기본","prices":{"a":12000}}],"priceImages":[],"priceText":"","title":"","roomTypes":[],"roomPricing":{"base":{},"exceptions":[]}},
             "blocks":[]}
            """;
        ProductUpsertRequest req = service.upsertFromProposed(proposed);
        assertThat(req.name()).isEqualTo("바뀜");
        assertThat(req.saleStart()).isEqualTo(java.time.LocalDate.of(2026, 6, 1));
        assertThat(req.saleEnd()).isEqualTo(java.time.LocalDate.of(2026, 6, 3));
        assertThat(req.priceFrom()).isEqualTo(12000);
    }

    @Test
    void 반려도_대기중일_때만() {
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "delete", null, null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markRejected(eq(r.getId()), eq("u-admin"), any(), anyString())).thenReturn(0);

        Throwable e = catchThrowable(() -> service.reject(ADMIN, r.getId(), "사유"));
        assertThat(((ProductException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(notifier, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 반려_사유가_너무_길면_400() {
        Throwable e = catchThrowable(() -> service.reject(ADMIN, "r1", "가".repeat(ChangeRequestService.REASON_MAX + 1)));
        assertThat(((ProductException) e).code()).isEqualTo("tooLong");
    }

    @Test
    void 공통_에러응답은_코드면_code_문장이면_숨김() {
        ApiExceptionHandler h = new ApiExceptionHandler();
        assertThat(h.onStatus(new ResponseStatusException(HttpStatus.FORBIDDEN, "roleMismatch")).getBody())
                .isEqualTo(Map.of("code", "roleMismatch"));
        assertThat(h.onStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다: b1")).getBody())
                .isEqualTo(Map.of("status", 404));
    }
}
