package com.eshop.service;

import com.eshop.dto.GatewayResult;
import com.eshop.enums.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 — Unit tests for {@link MockPaymentGateway}.
 *
 * <p><b>Why no Math.random() stubbing:</b> the gateway simulates a 1% random failure via
 * {@code Math.random()}. Stubbing it with {@code Mockito.mockStatic(Math.class)} (even in
 * {@code stubOnly()} mode) crashes the forked test JVM with a {@code StackOverflowError}
 * on JDK 21 + Mockito inline mock maker: {@code java.lang.Math} is a bootstrap class and
 * instrumenting it re-routes internal JDK Math calls through Mockito's dispatcher, which
 * recurses until the stack overflows. Probed and confirmed, so we do not mock Math.
 *
 * <p><b>Deterministic-by-construction instead:</b> every test asserts the outcome
 * <i>invariant</i> that must hold regardless of which random branch fires:
 * <ul>
 *   <li>success  &rarr; the method-specific status and a {@code MOCK-} transaction id</li>
 *   <li>failure  &rarr; {@code "Simulazione errore gateway"} and a {@code null} transaction id</li>
 * </ul>
 * Each check is repeated {@code 3} times per method: a regression in the mapping would go
 * unnoticed only if the 1% branch fired on <i>all</i> 3 calls (probability 10<sup>-6</sup>).
 *
 * <p>The gateway <i>failure</i> path at the system level is covered deterministically in
 * {@code OrderServiceTest#completePaymentGatewayFailure}, which uses a mocked
 * {@code PaymentGatewayService} returning a failure result.
 *
 * <p>Note: each call takes ~200ms (simulated gateway latency via Thread.sleep).
 */
@DisplayName("MockPaymentGateway (invariant checks — 1% random branch cannot be pinned on this JVM)")
class MockPaymentGatewayTest {

    private static final int CALLS_PER_METHOD = 3;

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    /**
     * Asserts the invariant for one call:
     * success -> expected status + MOCK-* tx id; failure -> error status + null tx id.
     */
    private void assertInvariant(GatewayResult result, PaymentMethod method, String expectedStatus) {
        if (result.success()) {
            assertThat(result.status())
                    .as("success status for %s", method)
                    .isEqualTo(expectedStatus);
            assertThat(result.transactionId())
                    .as("transaction id for %s", method)
                    .startsWith("MOCK-");
        } else {
            assertThat(result.status())
                    .as("failure status for %s", method)
                    .isEqualTo("Simulazione errore gateway");
            assertThat(result.transactionId())
                    .as("failure transaction id for %s", method)
                    .isNull();
        }
    }

    private void assertInvariants(PaymentMethod method, String expectedStatus) {
        for (int i = 0; i < CALLS_PER_METHOD; i++) {
            GatewayResult result = gateway.processPayment(
                    method, new BigDecimal("25.00"), Map.of("card", "4111111111111111"));
            assertInvariant(result, method, expectedStatus);
        }
    }

    @Test
    @DisplayName("CREDIT_CARD -> success status CAPTURED")
    void creditCardCaptured() {
        assertInvariants(PaymentMethod.CREDIT_CARD, "CAPTURED");
    }

    @Test
    @DisplayName("PAYPAL -> success status CAPTURED")
    void paypalCaptured() {
        assertInvariants(PaymentMethod.PAYPAL, "CAPTURED");
    }

    @Test
    @DisplayName("COD -> success status CAPTURED")
    void codCaptured() {
        assertInvariants(PaymentMethod.COD, "CAPTURED");
    }

    @Test
    @DisplayName("BANK_TRANSFER -> success status AUTHORIZED (not captured)")
    void bankTransferAuthorized() {
        assertInvariants(PaymentMethod.BANK_TRANSFER, "AUTHORIZED");
    }
}
