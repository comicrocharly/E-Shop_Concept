package com.eshop.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 — Integrazione full-stack: profilo utente ({@code /api/users}), numeri di
 * telefono ({@code /api/users/{userId}/phone/me}) e indirizzi
 * ({@code /api/users/{userId}/address/me}).
 *
 * <p>Nota: i controller phone/address hanno il path base
 * {@code /api/users/{userId}/...} ma il vero ID utente viene da {@code ?testUserId=}
 * (o dal SecurityContext): la path variable è obbligatoria ma non utilizzata
 * (comportamento attuale, qui semplicemente soddisfata).
 */
class UserIntegrationTest extends IntegrationTestSupport {

    private static final String PW = "secret123";

    private Auth newUser() throws Exception {
        return login(register(PW), PW);
    }

    // ==================== GET /me ====================

    @Test
    void me_200_fullShape() throws Exception {
        Auth a = newUser();
        mockMvc.perform(get("/api/users/me").param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.id()))
                .andExpect(jsonPath("$.username").value(a.username()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.cart.id").isNumber())
                .andExpect(jsonPath("$.cart.items").isArray())
                .andExpect(jsonPath("$.phoneNumbers").isArray())
                .andExpect(jsonPath("$.addresses").isArray())
                .andExpect(jsonPath("$.password").doesNotExist()); // mai esposto
    }

    @Test
    void me_viaBearer_jwt_200() throws Exception {
        // path CurrentUser (senza ?testUserId) con Bearer JWT: funziona full-stack
        Auth a = newUser();
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(a.username()));
    }

    @Test
    void me_unknownUser_500_currentBehavior() throws Exception {
        // ⚠ Comportamento attuale: utente inesistente → RuntimeException nel
        // controller → handler generico → 500 (non 404).
        mockMvc.perform(get("/api/users/me").param("testUserId", "999999999"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("User not found: 999999999"));
    }

    @Test
    void me_admin_200_roleAdmin() throws Exception {
        Auth adminAuth = admin();
        mockMvc.perform(get("/api/users/me").param("testUserId", String.valueOf(adminAuth.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // ==================== PUT /me/profile ====================

    @Test
    void profile_changeEmail_200() throws Exception {
        Auth a = newUser();
        String newEmail = a.username() + "-new@profile.test";

        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", newEmail))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));
        assertThat(userRepository.findByUsername(a.username()).orElseThrow().getEmail()).isEqualTo(newEmail);
    }

    @Test
    void profile_changeEmailDuplicate_400() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        String bEmail = userRepository.findByUsername(b.username()).orElseThrow().getEmail();

        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", bEmail))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email già in uso: " + bEmail));
    }

    @Test
    void profile_changePassword_200_loginWithNewPassword() throws Exception {
        Auth a = newUser();

        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "password", "newpass-77",
                                "currentPassword", PW))))
                .andExpect(status().isOk());

        // la nuova password funziona, la vecchia no
        Auth again = login(a.username(), "newpass-77");
        assertThat(again.id()).isEqualTo(a.id());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", a.username(), "password", PW))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Credenziali non valide"));
    }

    @Test
    void profile_changePassword_missingCurrent_400() throws Exception {
        Auth a = newUser();
        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "newpass-77"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("È richiesta la password corrente per cambiare la password"));
    }

    @Test
    void profile_changePassword_wrongCurrent_400() throws Exception {
        Auth a = newUser();
        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "password", "newpass-77",
                                "currentPassword", "not-the-current"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password corrente non è corretta"));
    }

    @Test
    void profile_changePassword_shortNew_400() throws Exception {
        Auth a = newUser();
        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "password", "12345",
                                "currentPassword", PW))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password deve avere almeno 6 caratteri"));
    }

    @Test
    void profile_usernameKeyIgnored_currentBehavior() throws Exception {
        // ⚠ Comportamento attuale: updateProfile legge solo "email" e "password";
        // la chiave "username" è ignorata silenziosamente.
        Auth a = newUser();
        mockMvc.perform(put("/api/users/me/profile")
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "should-be-ignored"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(a.username()));
    }

    // ==================== PHONE ====================

    @Test
    void phone_addListDelete_flow() throws Exception {
        Auth a = newUser();
        String base = "/api/users/" + a.id() + "/phone/me";

        mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "countryPrefix", "+39", "number", "3331234567", "phoneType", "MOBILE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("3331234567"))
                .andExpect(jsonPath("$.phoneType").value("MOBILE"));

        long id1 = readJson(mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "countryPrefix", "+1", "number", "5550001111", "phoneType", "FIXED"))))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();

        // lista: 2 numeri
        JsonNode list = readJson(mockMvc.perform(get(base)
                        .param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(2);

        // cancella uno → 204
        mockMvc.perform(delete(base + "/" + id1)
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isNoContent());
        assertThat(readJson(mockMvc.perform(get(base)
                        .param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    void phone_addMissingFields_400_validation() throws Exception {
        Auth a = newUser();
        String base = "/api/users/" + a.id() + "/phone/me";
        mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.countryPrefix").exists())
                .andExpect(jsonPath("$.number").exists())
                .andExpect(jsonPath("$.phoneType").exists());
    }

    @Test
    void phone_addInvalidType_400() throws Exception {
        Auth a = newUser();
        String base = "/api/users/" + a.id() + "/phone/me";
        mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "countryPrefix", "+39", "number", "3331234567", "phoneType", "SATELLITE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void phone_deleteOtherUserPhone_500_currentBehavior() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        String baseA = "/api/users/" + a.id() + "/phone/me";
        String baseB = "/api/users/" + b.id() + "/phone/me";

        long phoneId = readJson(mockMvc.perform(post(baseA)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "countryPrefix", "+39", "number", "3331234567", "phoneType", "MOBILE"))))
                .andReturn().getResponse().getContentAsString()).path("id").asLong();

        // B non può cancellare il numero di A (ownership nel servizio):
        // ⚠ Comportamento attuale (coerente con S3): EntityNotFoundException non
        // è mappata dal GlobalExceptionHandler → handler generico → 500 (non 404).
        mockMvc.perform(delete(baseB + "/" + phoneId)
                        .param("testUserId", String.valueOf(b.id())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Phone number not found or not owned by user"));
    }

    // ==================== ADDRESS ====================

    @Test
    void address_addListDelete_flow() throws Exception {
        Auth a = newUser();
        String base = "/api/users/" + a.id() + "/address/me";
        String body = json(Map.of(
                "street", "Via Roma", "streetNumber", 5,
                "postalCode", "20100", "city", "Milano", "country", "IT"));

        mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.street").value("Via Roma"))
                .andExpect(jsonPath("$.streetNumber").value(5));

        long id1 = readJson(mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn().getResponse().getContentAsString()).path("id").asLong();

        assertThat(readJson(mockMvc.perform(get(base)
                        .param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString())).hasSize(2);

        mockMvc.perform(delete(base + "/" + id1)
                        .param("testUserId", String.valueOf(a.id())))
                .andExpect(status().isNoContent());
        assertThat(readJson(mockMvc.perform(get(base)
                        .param("testUserId", String.valueOf(a.id())))
                .andReturn().getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    void address_addMissingFields_400_validation() throws Exception {
        Auth a = newUser();
        String base = "/api/users/" + a.id() + "/address/me";
        mockMvc.perform(post(base)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.street").exists())
                .andExpect(jsonPath("$.postalCode").exists())
                .andExpect(jsonPath("$.city").exists())
                .andExpect(jsonPath("$.country").exists())
                // ⚠ Comportamento attuale: streetNumber=null NON scatta la validazione
                // (solo @Min, niente @NotNull) → chiave streetNumber assente.
                .andExpect(jsonPath("$.streetNumber").doesNotExist());
    }

    @Test
    void address_deleteOtherUserAddress_500_currentBehavior() throws Exception {
        Auth a = newUser();
        Auth b = newUser();
        String baseA = "/api/users/" + a.id() + "/address/me";
        String baseB = "/api/users/" + b.id() + "/address/me";
        String body = json(Map.of(
                "street", "Viale 1° Maggio", "streetNumber", 10,
                "postalCode", "00100", "city", "Roma", "country", "IT"));

        long addressId = readJson(mockMvc.perform(post(baseA)
                        .param("testUserId", String.valueOf(a.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn().getResponse().getContentAsString()).path("id").asLong();

        // ⚠ Comportamento attuale (coerente con S3): EntityNotFoundException non
        // mappata → 500 (non 404).
        mockMvc.perform(delete(baseB + "/" + addressId)
                        .param("testUserId", String.valueOf(b.id())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Address not found or not owned by user"));
    }
}
