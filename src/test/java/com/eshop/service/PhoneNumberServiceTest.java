package com.eshop.service;

import com.eshop.dto.AddPhoneNumberRequest;
import com.eshop.dto.PhoneNumberResponse;
import com.eshop.entity.PhoneNumber;
import com.eshop.entity.PhoneNumber.PhoneType;
import com.eshop.entity.User;
import com.eshop.repository.PhoneNumberRepository;
import com.eshop.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 — Unit tests for {@link PhoneNumberService} (mocked repositories, real entities).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhoneNumberServiceTest {

    @Mock
    private PhoneNumberRepository phoneRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PhoneNumberService phoneNumberService;

    // Deliberately built with the no-args constructor (not @Builder): the builder
    // leaves phoneNumbers/addresses/orders null (no @Builder.Default on those fields),
    // while the service does user.getPhoneNumbers().add(...).
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setUsername("carlo");
        user.setEmail("carlo@test.local");
        user.setPassword("h");
        user.setRole("USER");
    }

    private PhoneNumber phone(Long id, String number) {
        return PhoneNumber.builder()
                .id(id)
                .countryPrefix("+39")
                .number(number)
                .phoneType(PhoneType.MOBILE)
                .user(user)
                .build();
    }

    // ==================== FIND ====================

    @Nested
    @DisplayName("findByUserId")
    class Find {

        @Test
        @DisplayName("maps phones to PhoneNumberResponse")
        void findByUserId() {
            when(phoneRepository.findByUserId(1L))
                    .thenReturn(List.of(phone(10L, "3331112223"), phone(11L, "0559998887")));

            List<PhoneNumberResponse> response = phoneNumberService.findByUserId(1L);

            assertThat(response).hasSize(2);
            assertThat(response.get(0).getId()).isEqualTo(10L);
            assertThat(response.get(0).getCountryPrefix()).isEqualTo("+39");
            assertThat(response.get(0).getNumber()).isEqualTo("3331112223");
            assertThat(response.get(0).getPhoneType()).isEqualTo(PhoneType.MOBILE.name());
            assertThat(response.get(1).getNumber()).isEqualTo("0559998887");
        }

        @Test
        @DisplayName("no phones -> empty list")
        void findByUserIdEmpty() {
            when(phoneRepository.findByUserId(1L)).thenReturn(List.of());

            assertThat(phoneNumberService.findByUserId(1L)).isEmpty();
        }
    }

    // ==================== ADD ====================

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("saves new phone and links it to the user")
        void addSuccess() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(phoneRepository.save(any(PhoneNumber.class))).thenAnswer(inv -> {
                PhoneNumber p = inv.getArgument(0);
                p.setId(42L);
                return p;
            });

            PhoneNumberResponse response =
                    phoneNumberService.add(1L, new AddPhoneNumberRequest("+39", "3331234567", "MOBILE"));

            assertThat(response.getId()).isEqualTo(42L);
            assertThat(response.getCountryPrefix()).isEqualTo("+39");
            assertThat(response.getNumber()).isEqualTo("3331234567");
            assertThat(response.getPhoneType()).isEqualTo(PhoneType.MOBILE.name());
            assertThat(user.getPhoneNumbers()).hasSize(1);
            assertThat(user.getPhoneNumbers().get(0).getNumber()).isEqualTo("3331234567");
            assertThat(user.getPhoneNumbers().get(0).getPhoneType()).isEqualTo(PhoneType.MOBILE);
            verify(phoneRepository).save(any(PhoneNumber.class));
        }

        @Test
        @DisplayName("invalid phoneType string -> IllegalArgumentException (enum valueOf)")
        void addInvalidPhoneType() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    phoneNumberService.add(1L, new AddPhoneNumberRequest("+39", "123", "LASER")))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(phoneRepository, never()).save(any(PhoneNumber.class));
        }

        @Test
        @DisplayName("unknown user -> EntityNotFoundException")
        void addUnknownUser() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    phoneNumberService.add(404L, new AddPhoneNumberRequest("+39", "123", "MOBILE")))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(phoneRepository, never()).save(any(PhoneNumber.class));
        }
    }

    // ==================== DELETE ====================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("owner can delete their own phone")
        void deleteSuccess() {
            PhoneNumber phone = phone(10L, "3331112223");
            when(phoneRepository.findById(10L)).thenReturn(Optional.of(phone));

            phoneNumberService.delete(1L, 10L);

            verify(phoneRepository).delete(phone);
        }

        @Test
        @DisplayName("unknown phone -> EntityNotFoundException")
        void deleteNotFound() {
            when(phoneRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> phoneNumberService.delete(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(phoneRepository, never()).delete(any(PhoneNumber.class));
        }

        @Test
        @DisplayName("phone owned by another user -> EntityNotFoundException")
        void deleteNotOwner() {
            User other = new User();
            other.setId(2L);
            PhoneNumber otherPhone = PhoneNumber.builder()
                    .id(10L).number("111").phoneType(PhoneType.FIXED).user(other).build();
            when(phoneRepository.findById(10L)).thenReturn(Optional.of(otherPhone));

            assertThatThrownBy(() -> phoneNumberService.delete(1L, 10L))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(phoneRepository, never()).delete(any(PhoneNumber.class));
        }
    }
}
