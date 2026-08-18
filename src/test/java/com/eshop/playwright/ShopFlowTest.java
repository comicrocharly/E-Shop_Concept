package com.eshop.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Page.LocatorOptions;
import com.microsoft.playwright.Page.WaitForSelectorOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 — ShopFlow: register → login → catalog → cart → checkout → paid order.
 *
 * <p>End-to-end coverage of the user journey against the live app (dev profile):
 * <ul>
 *   <li>auth: register/login via the form (incl. the 401 error toast)</li>
 *   <li>catalog: articles (seeded through the ADMIN API — the public API has no article
 *       writes) with price + Italian stock labels, and live search filtering</li>
 *   <li>cart: add-to-cart badge (item <i>types</i>), cart modal contents, quantities</li>
 *   <li>checkout: cart modal → cart screen → payment modal, credit card and COD payment
 *       (retry-tolerant against the gateway's 1% random failure), order status PROCESSING
 *       in "Ordini" and in the admin tab, stock decremented in the catalog</li>
 * </ul>
 *
 * <p>Disabled by default; see {@link PlaywrightBase} for how to run the S5 suite manually.
 * A fresh browser context is used per test; the shared buyer is created once per class
 * via the API (keeps register rate-limit pressure low across the suite).
 */
class ShopFlowTest extends PlaywrightBase {

    private static final String BUYER_PASSWORD = "E2ePass!123";

    private static String buyerUsername;
    private static String buyerToken;

    @BeforeAll
    void createSharedBuyer() {
        buyerUsername = "e2e-flow-buyer-" + RUN_ID;
        buyerToken = registerAndLoginViaApi(buyerUsername, buyerUsername + "@e2e.local", BUYER_PASSWORD);
    }

    @BeforeEach
    void resetBuyerCart() {
        clearCartViaApi(buyerToken);
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    @Test
    void registerViaUi_landsInCatalog() {
        String user = "e2e-flow-user-" + RUN_ID;
        registerViaUi(user, user + "@e2e.local", BUYER_PASSWORD);
        assertThat(page.locator("#appSection")).isVisible();
        assertThat(page.locator("#authSection")).isHidden();
        assertThat(page.locator("#currentUsername")).hasText(user);
        assertThat(page.locator("#adminBadge")).isHidden();
        assertThat(page.locator(".nav-tab", new LocatorOptions().setHasText("Catalogo"))).isVisible();
    }

    @Test
    void loginViaUi_wrongPassword_showsErrorToast_andStaysOnAuth() {
        page.fill("#username", buyerUsername);
        page.fill("#password", "WrongPass!123");
        page.click("#authSubmit");
        assertThat(page.locator(".toast.error")).isVisible();
        assertThat(page.locator(".toast.error").first()).containsText("Credenziali non valide");
        assertThat(page.locator("#authSection")).isVisible();
        assertThat(page.locator("#appSection")).isHidden();
        // let the toast disappear (3s lifetime) before the context is closed
        page.waitForSelector(".toast.error",
                new WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED).setTimeout(10_000));
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    @Test
    void catalog_showsCreatedArticle_withPriceAndStockText() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-catalog-" + RUN_ID;
        long article = createArticleViaApi(token + " Widget", "E2E article", 10.50, 3);
        searchCatalog(token);
        Locator card = productCard(article);
        assertThat(card).isVisible();
        assertThat(card.locator(".product-name")).hasText(token + " Widget");
        assertThat(card.locator(".product-price")).hasText("€10.50");
        assertThat(card.locator(".product-stock")).hasText("3 disponibili"); // stock < 5 → "N disponibili"
    }

    @Test
    void catalog_searchFiltersByToken_andHighStockText() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-filt-" + RUN_ID;
        long a = createArticleViaApi(token + " Uno", "E2E article", 1.00, 10);
        long b = createArticleViaApi(token + " Due", "E2E article", 2.00, 5);
        long decoy = createArticleViaApi("e2e-decoy-" + RUN_ID, "E2E article", 3.00, 5);
        searchCatalog(token);
        assertThat(page.locator(".product-card[data-product-id='" + a + "']")).isVisible();
        assertThat(page.locator(".product-card[data-product-id='" + b + "']")).isVisible();
        assertThat(page.locator(".product-card[data-product-id='" + decoy + "']")).hasCount(0);
        assertThat(page.locator(".product-card")).hasCount(2);
        assertThat(productCard(b).locator(".product-stock")).hasText("Disponibile"); // stock >= 5
    }

    // ------------------------------------------------------------------
    // Cart
    // ------------------------------------------------------------------

    @Test
    void addToCart_updatesBadgeAndCartModal() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-cart-" + RUN_ID;
        long article = createArticleViaApi(token + " Item", "E2E article", 12.00, 10);
        searchCatalog(token);
        assertThat(productCard(article)).isVisible();
        addToCartViaUi(article, 1, buyerToken);
        assertThat(page.locator("#cartBadge")).hasText("1");
        page.click("#cartToggleBtn");
        assertThat(page.locator("#cartModal.open")).isVisible();
        assertThat(page.locator(".cart-modal-item-name")).hasText(token + " Item");
        assertThat(page.locator(".cart-modal-item-unit")).hasText("€12.00 cad.");
        assertThat(page.locator(".cart-modal-item-qty")).hasText("1");
        assertThat(page.locator("#cartModalTotal")).hasText("€12.00");
        page.click("#cartModal .cart-modal-close");
        assertThat(page.locator("#cartModal.open")).isHidden();
    }

    @Test
    void addSameArticleTwice_quantityDoubles_inCartModal() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-qty-" + RUN_ID;
        long article = createArticleViaApi(token + " Item", "E2E article", 12.00, 10);
        searchCatalog(token);
        assertThat(productCard(article)).isVisible();
        addToCartViaUi(article, 2, buyerToken);
        assertThat(page.locator("#cartBadge")).hasText("1"); // badge = item TYPES, not total qty
        page.click("#cartToggleBtn");
        assertThat(page.locator("#cartModal.open")).isVisible();
        assertThat(page.locator(".cart-modal-item-qty")).hasText("2");
        assertThat(page.locator(".cart-modal-item-total")).hasText("€24.00");
        assertThat(page.locator("#cartModalTotal")).hasText("€24.00");
    }

    // ------------------------------------------------------------------
    // Checkout (full shop flow)
    // ------------------------------------------------------------------

    @Test
    void fullShopFlow_registerCatalogCartPaymentOrder() {
        String user = "e2e-flow2-" + RUN_ID;
        registerViaUi(user, user + "@e2e.local", BUYER_PASSWORD);
        String token = "e2e-flow3-" + RUN_ID;
        long alpha = createArticleViaApi(token + " Alpha", "E2E article", 10.00, 3);
        long beta = createArticleViaApi(token + " Beta", "E2E article", 5.50, 5);
        searchCatalog(token);
        assertThat(productCard(alpha)).isVisible();
        assertThat(productCard(beta)).isVisible();

        CheckoutResult checkout = completeCheckout(apiLogin(user, BUYER_PASSWORD),
                new long[]{alpha, 1, beta, 2}, null); // null → default CREDIT_CARD

        // Payment success screen (label/value pairs are separate flex spans → assert values)
        assertThat(page.locator(".payment-status-badge.success")).isVisible();
        assertThat(page.locator("#paymentModalBody")).containsText(Pattern.compile("MOCK-[0-9A-F]{8}"));
        assertThat(page.locator("#paymentModalBody")).containsText("Carta di credito");
        assertEquals("€21.00", checkout.cartModalTotal());
        assertTrue(checkout.cartScreenSummary().contains("€21.00"),
                "cart screen summary: " + checkout.cartScreenSummary());

        // "Vai ai miei ordini" → the order appears with status PROCESSING
        page.locator("#paymentModalBody button", new LocatorOptions().setHasText("Vai ai miei ordini")).click();
        assertThat(page.locator("#ordersList")).isVisible();
        Locator myOrder = page.locator("#ordersList .order-item",
                new LocatorOptions().setHasText("Ordine #" + checkout.orderId()));
        assertThat(myOrder).hasCount(1);
        assertThat(myOrder.locator(".order-status.status-processing")).hasText("PROCESSING");
        // /orders/my returns nested articles → item names render correctly on the buyer side.
        assertThat(myOrder.locator(".order-items")).containsText(token + " Alpha x1");
        assertThat(myOrder.locator(".order-items")).containsText(token + " Beta x2");
        assertThat(myOrder.locator(".order-total")).hasText("Totale: €21.00");

        // Back to the catalog: stock decremented. Bug B1: each 1% gateway failure also
        // re-added the ordered quantities (rollback on never-decremented stock), so the
        // expected value accounts for the failures that actually happened.
        page.locator(".nav-tab", new LocatorOptions().setHasText("Catalogo")).click();
        int f = checkout.gatewayFailures();
        assertThat(productCard(alpha).locator(".product-stock")).hasText((3 - 1 + f) + " disponibili");
        assertThat(productCard(beta).locator(".product-stock")).hasText((5 - 2 + 2 * f) + " disponibili");
    }

    @Test
    void checkout_codPaymentMethod_succeeds_andOrderIsProcessing() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-cod-" + RUN_ID;
        long article = createArticleViaApi(token + " Cod Item", "E2E article", 7.25, 4);
        searchCatalog(token);
        assertThat(productCard(article)).isVisible();

        CheckoutResult checkout = completeCheckout(buyerToken, new long[]{article, 1}, "#pm_cod");

        assertEquals("€7.25", checkout.cartModalTotal());
        assertThat(page.locator("#paymentModalBody")).containsText(Pattern.compile("MOCK-[0-9A-F]{8}"));
        assertThat(page.locator("#paymentModalBody")).containsText("Contrassegno");
        page.locator("#paymentModalBody button", new LocatorOptions().setHasText("Torna al negozio")).click();

        assertThat(page.locator("#cartBadge")).hasText("0"); // cart cleared after payment
        openMyOrdersTab();
        Locator myOrder = page.locator("#ordersList .order-item",
                new LocatorOptions().setHasText("Ordine #" + checkout.orderId()));
        assertThat(myOrder.locator(".order-status.status-processing")).hasText("PROCESSING");
        assertThat(myOrder.locator(".order-total")).hasText("Totale: €7.25");
    }

    @Test
    void adminSeesPaidOrder_inAdminTab() {
        loginViaUi(buyerUsername, BUYER_PASSWORD);
        String token = "e2e-adm-" + RUN_ID;
        long article = createArticleViaApi(token + " Admin Item", "E2E article", 3.00, 2);
        searchCatalog(token);
        assertThat(productCard(article)).isVisible();

        CheckoutResult checkout = completeCheckout(buyerToken, new long[]{article, 1}, null);
        page.locator("#paymentModalBody button", new LocatorOptions().setHasText("Torna al negozio")).click();

        // Switch user (UI test helper) and log in as ADMIN: the paid order must be
        // visible in the admin orders list (newest first, page 0).
        // NOTE (known UI/DTO mismatch, asserted as-is):
        //  - the admin UI renders "Utente: N/A" for every order — the DTO exposes the
        //    username at top level while the template reads order.user?.username
        //  - item names render as the fallback "Articolo" — the DTO exposes a flat
        //    `articleName` while the template reads item.articles?.name
        page.evaluate("() => window.__eshopTest.clearAuth()");
        assertThat(page.locator("#authSection")).isVisible();
        loginViaUi(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(page.locator("#adminBadge")).isVisible();
        assertThat(page.locator("#adminTab")).isVisible();

        page.click("#adminTab");
        assertThat(page.locator("#adminTabContent.active")).isVisible();
        Locator adminOrder = page.locator("#adminOrdersList .admin-order-item",
                new LocatorOptions().setHasText("Ordine #" + checkout.orderId()));
        assertThat(adminOrder).hasCount(1);
        assertThat(adminOrder.locator(".order-id")).hasText("Ordine #" + checkout.orderId());
        assertThat(adminOrder.locator(".order-status.status-processing")).hasText("PROCESSING");
        assertThat(adminOrder).containsText("Articolo x1 @ €3.00");
        assertThat(adminOrder).containsText("Totale: €3.00");
    }
}
