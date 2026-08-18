package com.eshop.enums;

public enum PaymentMethod {
    CREDIT_CARD("Carta di credito"),
    PAYPAL("PayPal"),
    COD("Contrassegno"),
    BANK_TRANSFER("Bonifico bancario");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
