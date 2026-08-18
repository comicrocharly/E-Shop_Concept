package com.eshop.service;

import com.eshop.dto.GatewayResult;
import com.eshop.enums.PaymentMethod;
import com.eshop.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MockPaymentGateway implements PaymentGatewayService {

    @Override
    public GatewayResult processPayment(PaymentMethod method, BigDecimal amount, Map<String, String> details) {
        try {
            Thread.sleep(200); // simula chiamata HTTP
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // 1% probabilità di errore (per testare gestione errori)
        if (Math.random() < 0.01) {
            log.warn("MockPaymentGateway: pagamento fallito (simulazione) amount={}", amount);
            return new GatewayResult(false, "Simulazione errore gateway", null);
        }

        String transactionId = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        PaymentStatus status;

        // Bonifico: solo autorizzato, cattura manuale dopo
        if (method == PaymentMethod.BANK_TRANSFER) {
            status = PaymentStatus.AUTHORIZED;
        }
        // Contrassegno: cattura diretta
        else if (method == PaymentMethod.COD) {
            status = PaymentStatus.CAPTURED;
        }
        // Carta e PayPal: cattura diretta nel mock
        else {
            status = PaymentStatus.CAPTURED;
        }

        log.info("MockPaymentGateway: pagamento {} method={} amount={} txn={}",
                status, method, amount, transactionId);

        return new GatewayResult(true, status.name(), transactionId);
    }
}
