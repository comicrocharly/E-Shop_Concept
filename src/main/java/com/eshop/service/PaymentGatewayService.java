package com.eshop.service;

import com.eshop.dto.GatewayResult;
import com.eshop.enums.PaymentMethod;
import java.math.BigDecimal;
import java.util.Map;

public interface PaymentGatewayService {

    /**
     * Processa un pagamento. Simula un gateway esterno.
     *
     * @param method   metodo di pagamento
     * @param amount   importo
     * @param details  dati specifici (es. last4 card, email PayPal, null per COD)
     * @return risultato del pagamento
     */
    GatewayResult processPayment(PaymentMethod method, BigDecimal amount, Map<String, String> details);
}
