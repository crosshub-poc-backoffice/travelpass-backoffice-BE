package com.crosshub.backoffice.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** 목록 공통 응답 봉투. */
public record PageResponse<T>(List<T> rows, long total, int page, int size) {
    public static <E, T> PageResponse<T> of(Page<E> p, List<T> rows) {
        return new PageResponse<>(rows, p.getTotalElements(), p.getNumber() + 1, p.getSize());
    }
}
