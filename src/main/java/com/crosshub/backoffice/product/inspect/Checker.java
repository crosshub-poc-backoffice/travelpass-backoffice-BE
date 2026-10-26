package com.crosshub.backoffice.product.inspect;

import java.util.List;

/** 검사기 하나. rule = 산수로 확정, ai = Solar 판정 */
public interface Checker {
    String code();
    boolean isAi();
    boolean appliesTo(InspectTarget t);
    List<Finding> run(InspectTarget t);
}
