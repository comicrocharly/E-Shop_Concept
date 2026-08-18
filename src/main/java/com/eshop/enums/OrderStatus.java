package com.eshop.enums;

/**
 * Stati possibili di un ordine.
 * 
 * Transizione: PENDING → PROCESSING → SHIPPED → DELIVERED → COMPLETED
 *                                              ↘ CANCELLED
 */
public enum OrderStatus {
    PENDING,       // Ordine appena creato
    PROCESSING,    // Ordine in fase di preparazione
    SHIPPED,       // Ordine spedito
    DELIVERED,     // Ordine consegnato
    COMPLETED,     // Ordine completato (es. pagato e consegnato)
    CANCELLED;     // Ordine cancellato

    /**
     * Verifica se un transizione di stato è valida.
     */
    public static boolean isValidTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) return false;
        if (from == to) return true; // no-op
        if (from == CANCELLED) return false; // cancellato non può essere modificato
        if (from == DELIVERED && to != COMPLETED) return false; // consegnato può solo andare a COMPLETED
        // Qualsiasi transizione verso CANCELLED è permessa
        if (to == CANCELLED) return true;
        // Qualsiasi transizione verso DELIVERED è permessa
        if (to == DELIVERED) return true;
        // Solo DELIVERED può andare a COMPLETED
        if (to == COMPLETED) return from == DELIVERED;
        // FORWARD progression: PENDING → PROCESSING → SHIPPED → ...
        return true;
    }

    /**
     * Icona descrittiva per lo stato.
     */
    public String icon() {
        return switch (this) {
            case PENDING -> "⏳";
            case PROCESSING -> "📦";
            case SHIPPED -> "🚚";
            case DELIVERED -> "✅";
            case COMPLETED -> "🏁";
            case CANCELLED -> "❌";
        };
    }

    /**
     * Etichetta leggibile in italiano.
     */
    public String label() {
        return switch (this) {
            case PENDING -> "In attesa";
            case PROCESSING -> "In preparazione";
            case SHIPPED -> "Spedito";
            case DELIVERED -> "Consegnato";
            case COMPLETED -> "Completato";
            case CANCELLED -> "Cancellato";
        };
    }
}
