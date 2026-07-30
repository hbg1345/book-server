package com.example.bookserver.purchase;

import lombok.Getter;

/**
 * The lifecycle states of a purchase (and of an individual book within it).
 * Stored in the DB as the enum name via MyBatis' default EnumTypeHandler.
 */
@Getter
public enum PurchaseState {
    PAYMENT_PENDING("결제 대기"),
    ORDERED("주문 완료"),
    PREPARING("상품 준비 중"),
    SHIPPING("배송 중"),
    DELIVERED("배송 완료"),
    CONFIRMED("구매 확정"),
    CANCEL_REQUESTED("취소 요청"),
    CANCELLED("취소 완료"),
    REFUND_REQUESTED("환불 요청"),
    REFUNDED("환불 완료");

    private final String label;

    PurchaseState(String label) {
        this.label = label;
    }
}
