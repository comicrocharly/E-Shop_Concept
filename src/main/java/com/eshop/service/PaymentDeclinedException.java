package com.eshop.service;

/**
 * Gateway ha rifiutato il pagamento.
 *
 * Lancia questa eccezione {@link OrderService#completePayment} (la transazione
 * viene rollata al 100%: nessun effetto collaterale). Il controller la
 * intercetta, annulla l'ordine in una transazione separata e risponde con 402.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
