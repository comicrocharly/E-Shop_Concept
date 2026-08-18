package com.eshop.integration;

import com.eshop.service.MockPaymentGateway;
import com.eshop.service.PaymentGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 — Unico test che NON mocka il gateway: verifica che, nel contesto completo,
 * il bean {@code PaymentGatewayService} iniettato in {@code OrderService} sia il
 * {@code @Service MockPaymentGateway} (il contratto dell'interfaccia è quindi
 * soddisfatto dall'implementazione reale, non da una mock inietta male).
 *
 * <p>Non esegue un pagamento completo per non dipendere dal fallimento casuale
 * 1% e dai 200ms di sleep del mock: la semantica del gateway coperta a livello
 * API è in {@link OrdersIntegrationTest} (con {@code @MockBean}).
 */
class PaymentGatewayRealIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentGatewayService paymentGateway;

    @Test
    void defaultGatewayBeanIsMockPaymentGateway() {
        assertThat(paymentGateway).isInstanceOf(MockPaymentGateway.class);
    }
}
