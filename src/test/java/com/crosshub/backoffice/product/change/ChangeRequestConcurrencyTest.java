package com.crosshub.backoffice.product.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crosshub.backoffice.account.Caller;
import com.crosshub.backoffice.common.ApiExceptionHandler;
import com.crosshub.backoffice.product.ProductException;
import com.crosshub.backoffice.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 변경요청 선점(조건부 UPDATE)·되돌리기, 공통 에러 응답 형식 */
class ChangeRequestConcurrencyTest {

    static final Caller ADMIN = new Caller("u-admin", "test-crosshub", "crosshub", null, null, List.of());

    ProductChangeRequestRepository requests = mock(ProductChangeRequestRepository.class);
    ChangeRequestService service = new ChangeRequestService(requests, mock(ProductRepository.class), new ObjectMapper());

    @Test
    void 이미_누가_처리했으면_승인은_404() {
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "hide", "hidden", null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markApproved(eq(r.getId()), eq("u-admin"), any())).thenReturn(0); // 경합에서 짐

        Throwable e = catchThrowable(() -> service.approve(ADMIN, r.getId()));
        assertThat(((ProductException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 반려도_대기중일_때만() {
        ProductChangeRequest r = ProductChangeRequest.pending("p1", "delete", null, null, "u-partner");
        when(requests.findById(r.getId())).thenReturn(Optional.of(r));
        when(requests.markRejected(eq(r.getId()), eq("u-admin"), any(), anyString())).thenReturn(0);

        Throwable e = catchThrowable(() -> service.reject(ADMIN, r.getId(), "사유"));
        assertThat(((ProductException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 반려_사유가_너무_길면_400() {
        Throwable e = catchThrowable(() -> service.reject(ADMIN, "r1", "가".repeat(ChangeRequestService.REASON_MAX + 1)));
        assertThat(((ProductException) e).code()).isEqualTo("tooLong");
    }

    @Test
    void 본인이_승인한_게_아니면_되돌리기_404() {
        when(requests.reopen("r1", "u-admin")).thenReturn(0);
        Throwable e = catchThrowable(() -> service.reopen(ADMIN, "r1"));
        assertThat(((ProductException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
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
