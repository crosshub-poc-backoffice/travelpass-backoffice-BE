package com.crosshub.backoffice;

import com.crosshub.backoffice.booking.Booking;
import com.crosshub.backoffice.product.Product;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 엔티티. 상태 전이 규칙이 엔티티 안에 있어서 mock 대신 진짜 엔티티를 쓴다
 * (mock이면 규칙 메서드가 아무것도 안 해서 검사가 통과한 것처럼 보인다).
 * 상태는 setter가 없으므로 DB에서 읽어 온 것처럼 필드에 직접 넣는다.
 */
public final class TestEntities {

    private TestEntities() {}

    public static Product product(String id, String partnerId, String status) {
        Product p = new Product();
        p.setId(id);
        p.setPartnerId(partnerId);
        p.setName("상품 " + id);
        ReflectionTestUtils.setField(p, "status", status);
        return p;
    }

    public static Booking booking(String id, String partnerId, String status, Integer cancelFee) {
        Booking b = org.springframework.beans.BeanUtils.instantiateClass(Booking.class); // JPA용 protected 생성자
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "partnerId", partnerId);
        ReflectionTestUtils.setField(b, "status", status);
        ReflectionTestUtils.setField(b, "cancelFee", cancelFee);
        ReflectionTestUtils.setField(b, "customerName", "고객");
        return b;
    }
}
