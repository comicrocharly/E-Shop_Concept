package com.eshop.service;

import com.eshop.dto.AddAddressRequest;
import com.eshop.dto.AddressResponse;
import com.eshop.entity.Address;
import com.eshop.entity.User;
import com.eshop.repository.AddressRepository;
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
 * S2 — Unit tests for {@link AddressService} (mocked repositories, real entities).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AddressService addressService;

    // Deliberately built with the no-args constructor (not @Builder): the builder
    // leaves phoneNumbers/addresses/orders null (no @Builder.Default on those fields),
    // while the service does user.getAddresses().add(...).
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

    private Address address(Long id) {
        return Address.builder()
                .id(id)
                .street("Via Roma")
                .streetNumber(7)
                .postalCode("50100")
                .city("Firenze")
                .country("IT")
                .user(user)
                .build();
    }

    // ==================== FIND ====================

    @Nested
    @DisplayName("findByUserId")
    class Find {

        @Test
        @DisplayName("maps addresses to AddressResponse")
        void findByUserId() {
            when(addressRepository.findByUserId(1L))
                    .thenReturn(List.of(address(10L), address(11L)));

            List<AddressResponse> response = addressService.findByUserId(1L);

            assertThat(response).hasSize(2);
            assertThat(response.get(0).getId()).isEqualTo(10L);
            assertThat(response.get(0).getStreet()).isEqualTo("Via Roma");
            assertThat(response.get(0).getStreetNumber()).isEqualTo(7);
            assertThat(response.get(0).getPostalCode()).isEqualTo("50100");
            assertThat(response.get(0).getCity()).isEqualTo("Firenze");
            assertThat(response.get(0).getCountry()).isEqualTo("IT");
        }

        @Test
        @DisplayName("no addresses -> empty list")
        void findByUserIdEmpty() {
            when(addressRepository.findByUserId(1L)).thenReturn(List.of());

            assertThat(addressService.findByUserId(1L)).isEmpty();
        }
    }

    // ==================== ADD ====================

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("saves new address and links it to the user")
        void addSuccess() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
                Address a = inv.getArgument(0);
                a.setId(42L);
                return a;
            });

            AddressResponse response =
                    addressService.add(1L, new AddAddressRequest("Via Roma", 7, "50100", "Firenze", "IT"));

            assertThat(response.getId()).isEqualTo(42L);
            assertThat(response.getStreet()).isEqualTo("Via Roma");
            assertThat(response.getStreetNumber()).isEqualTo(7);
            assertThat(response.getPostalCode()).isEqualTo("50100");
            assertThat(response.getCity()).isEqualTo("Firenze");
            assertThat(response.getCountry()).isEqualTo("IT");
            assertThat(user.getAddresses()).hasSize(1);
            assertThat(user.getAddresses().get(0).getStreet()).isEqualTo("Via Roma");
            verify(addressRepository).save(any(Address.class));
        }

        @Test
        @DisplayName("null streetNumber passes through unchecked (documents current behavior: " +
                "no service-level validation, @Min(1) is enforced only at the controller layer)")
        void addNullStreetNumber() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(addressRepository.save(any(Address.class))).thenAnswer(inv -> inv.getArgument(0));

            AddressResponse response =
                    addressService.add(1L, new AddAddressRequest("Via Roma", null, "50100", "Firenze", "IT"));

            assertThat(response.getStreetNumber()).isNull();
            assertThat(user.getAddresses().get(0).getStreetNumber()).isNull();
        }

        @Test
        @DisplayName("unknown user -> EntityNotFoundException")
        void addUnknownUser() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    addressService.add(404L, new AddAddressRequest("Via Roma", 7, "50100", "Firenze", "IT")))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(addressRepository, never()).save(any(Address.class));
        }
    }

    // ==================== DELETE ====================

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("owner can delete their own address")
        void deleteSuccess() {
            Address address = address(10L);
            when(addressRepository.findById(10L)).thenReturn(Optional.of(address));

            addressService.delete(1L, 10L);

            verify(addressRepository).delete(address);
        }

        @Test
        @DisplayName("unknown address -> EntityNotFoundException")
        void deleteNotFound() {
            when(addressRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> addressService.delete(1L, 99L))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(addressRepository, never()).delete(any(Address.class));
        }

        @Test
        @DisplayName("address owned by another user -> EntityNotFoundException")
        void deleteNotOwner() {
            User other = new User();
            other.setId(2L);
            Address otherAddress = Address.builder()
                    .id(10L).street("Via X").streetNumber(1)
                    .postalCode("00100").city("Roma").country("IT").user(other).build();
            when(addressRepository.findById(10L)).thenReturn(Optional.of(otherAddress));

            assertThatThrownBy(() -> addressService.delete(1L, 10L))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(addressRepository, never()).delete(any(Address.class));
        }
    }
}
