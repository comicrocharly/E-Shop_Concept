package com.eshop.enums;

public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED;

    public static boolean isValidTransition(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) return false;
        if (from == to) return true;
        if (from == REFUNDED) return false;
        if (from == FAILED) return false;
        if (from == PENDING) return to == AUTHORIZED || to == FAILED;
        if (from == AUTHORIZED) return to == CAPTURED || to == FAILED;
        if (from == CAPTURED) return to == REFUNDED;
        return false;
    }
}
