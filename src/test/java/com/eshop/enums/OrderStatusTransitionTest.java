package com.eshop.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 — Pure unit tests for {@link OrderStatus#isValidTransition(OrderStatus, OrderStatus)}.
 * No Spring context, no Mockito.
 */
@DisplayName("OrderStatus.isValidTransition")
class OrderStatusTransitionTest {

    // --- Valid transitions (as implemented in the enum) ---

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                // normal lifecycle
                Arguments.of(OrderStatus.PENDING, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED),
                Arguments.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED),
                Arguments.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED),
                // "jumps" are allowed by the current implementation (documented)
                Arguments.of(OrderStatus.PENDING, OrderStatus.SHIPPED),
                Arguments.of(OrderStatus.PENDING, OrderStatus.DELIVERED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.DELIVERED),
                // cancellations from non-terminal states
                Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
                // self-transitions are always valid
                Arguments.of(OrderStatus.PENDING, OrderStatus.PENDING),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.COMPLETED, OrderStatus.COMPLETED)
        );
    }

    // --- Invalid transitions ---

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                // CANCELLED is terminal: no outgoing transitions
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PENDING),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.SHIPPED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.DELIVERED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.COMPLETED),
                // DELIVERED can only go to COMPLETED (checked before the to==CANCELLED rule)
                Arguments.of(OrderStatus.DELIVERED, OrderStatus.PENDING),
                Arguments.of(OrderStatus.DELIVERED, OrderStatus.PROCESSING),
                Arguments.of(OrderStatus.DELIVERED, OrderStatus.SHIPPED),
                Arguments.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
                // COMPLETED is only reachable from DELIVERED
                Arguments.of(OrderStatus.PENDING, OrderStatus.COMPLETED),
                Arguments.of(OrderStatus.PROCESSING, OrderStatus.COMPLETED),
                Arguments.of(OrderStatus.SHIPPED, OrderStatus.COMPLETED),
                // null arguments are always invalid
                Arguments.of(null, OrderStatus.PENDING),
                Arguments.of(OrderStatus.PENDING, null)
        );
    }

    @ParameterizedTest
    @MethodSource("validTransitions")
    @DisplayName("valid: {0} -> {1}")
    void validTransition(OrderStatus from, OrderStatus to) {
        assertThat(OrderStatus.isValidTransition(from, to))
                .as("transition %s -> %s should be valid", from, to)
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidTransitions")
    @DisplayName("invalid: {0} -> {1}")
    void invalidTransition(OrderStatus from, OrderStatus to) {
        assertThat(OrderStatus.isValidTransition(from, to))
                .as("transition %s -> %s should be invalid", from, to)
                .isFalse();
    }
}
