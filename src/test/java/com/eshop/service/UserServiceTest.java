package com.eshop.service;

import com.eshop.dto.LoginRequest;
import com.eshop.dto.RegisterRequest;
import com.eshop.entity.User;
import com.eshop.repository.CartRepository;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2 — Unit tests for {@link UserService} (mocked repositories, no Spring context).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .username("carlo")
                .email("carlo@test.local")
                .password("hashed-password")
                .role("USER")
                .build();
    }

    // ==================== REGISTER ====================

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("creates user with encoded password and USER role")
        void registerSuccess() {
            RegisterRequest request = new RegisterRequest("carlo", "secret1", "carlo@test.local");
            when(userRepository.existsByUsername("carlo")).thenReturn(false);
            when(userRepository.existsByEmail("carlo@test.local")).thenReturn(false);
            when(passwordEncoder.encode("secret1")).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            User saved = userService.register(request);

            assertThat(saved.getUsername()).isEqualTo("carlo");
            assertThat(saved.getEmail()).isEqualTo("carlo@test.local");
            assertThat(saved.getPassword()).isEqualTo("hashed");
            assertThat(saved.getRole()).isEqualTo("USER");
            verify(passwordEncoder).encode("secret1");
        }

        @Test
        @DisplayName("duplicate username -> IllegalArgumentException")
        void registerDuplicateUsername() {
            RegisterRequest request = new RegisterRequest("carlo", "secret1", "other@test.local");
            when(userRepository.existsByUsername("carlo")).thenReturn(true);

            assertThatThrownBy(() -> userService.register(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Username già in uso");
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("duplicate email -> IllegalArgumentException")
        void registerDuplicateEmail() {
            RegisterRequest request = new RegisterRequest("newuser", "secret1", "carlo@test.local");
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.existsByEmail("carlo@test.local")).thenReturn(true);

            assertThatThrownBy(() -> userService.register(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email già registrata");
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // ==================== FIND / LOAD ====================

    @Nested
    @DisplayName("find / load")
    class FindLoad {

        @Test
        @DisplayName("findByUsername delegates to repository")
        void findByUsername() {
            when(userRepository.findByUsername("carlo")).thenReturn(Optional.of(user));

            assertThat(userService.findByUsername("carlo")).contains(user);
            assertThat(userService.findByUsername("nobody")).isEmpty();
        }

        @Test
        @DisplayName("loadUserByUsername returns Spring Security UserDetails (USER)")
        void loadUserByUsernameUser() {
            when(userRepository.findByUsername("carlo")).thenReturn(Optional.of(user));

            var details = userService.loadUserByUsername("carlo");

            assertThat(details.getUsername()).isEqualTo("carlo");
            assertThat(details.getPassword()).isEqualTo("hashed-password");
            assertThat(details.getAuthorities())
                    .extracting("authority").containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("loadUserByUsername maps admin role to ROLE_ADMIN")
        void loadUserByUsernameAdmin() {
            User admin = User.builder()
                    .id(2L).username("admin").email("a@test.local")
                    .password("h").role("ADMIN").build();
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

            var details = userService.loadUserByUsername("admin");

            assertThat(details.getAuthorities())
                    .extracting("authority").containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("loadUserByUsername unknown user -> UsernameNotFoundException")
        void loadUserByUsernameNotFound() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.loadUserByUsername("nobody"))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("findOrCreateUser returns existing user without saving")
        void findOrCreateUserExisting() {
            when(userRepository.findByUsername("carlo")).thenReturn(Optional.of(user));

            assertThat(userService.findOrCreateUser("carlo")).isSameAs(user);
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("findOrCreateUser creates guest user with generated email")
        void findOrCreateUserCreates() {
            when(userRepository.findByUsername("guest42")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(99L);
                return u;
            });

            User created = userService.findOrCreateUser("guest42");

            assertThat(created.getUsername()).isEqualTo("guest42");
            assertThat(created.getEmail()).isEqualTo("guest42@eshop.local");
            assertThat(created.getPassword()).isEqualTo("N/A");
            assertThat(created.getRole()).isEqualTo("USER");
        }
    }

    // ==================== AUTHENTICATE ====================

    @Nested
    @DisplayName("authenticate")
    class Authenticate {

        @Test
        @DisplayName("valid credentials -> user")
        void authenticateSuccess() {
            when(userRepository.findByUsername("carlo")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("plain-password", "hashed-password")).thenReturn(true);

            assertThat(userService.authenticate(new LoginRequest("carlo", "plain-password")))
                    .isSameAs(user);
        }

        @Test
        @DisplayName("wrong password -> IllegalArgumentException")
        void authenticateWrongPassword() {
            when(userRepository.findByUsername("carlo")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashed-password")).thenReturn(false);

            assertThatThrownBy(() -> userService.authenticate(new LoginRequest("carlo", "wrong")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credenziali non valide");
        }

        @Test
        @DisplayName("unknown user -> IllegalArgumentException")
        void authenticateUnknownUser() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.authenticate(new LoginRequest("nobody", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Credenziali non valide");
        }
    }

    // ==================== UPDATE PROFILE ====================

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("unknown user -> EntityNotFoundException")
        void updateProfileUnknownUser() {
            when(userRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.updateProfile(404L, Map.of()))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("change email to a free one")
        void updateProfileChangeEmail() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.existsByEmail("new@test.local")).thenReturn(false);
            when(userRepository.save(user)).thenReturn(user);

            userService.updateProfile(1L, Map.of("email", "new@test.local"));

            assertThat(user.getEmail()).isEqualTo("new@test.local");
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("change email to one already taken -> IllegalArgumentException")
        void updateProfileEmailAlreadyTaken() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.existsByEmail("taken@test.local")).thenReturn(true);

            assertThatThrownBy(() -> userService.updateProfile(1L, Map.of("email", "taken@test.local")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email già in uso");
        }

        @Test
        @DisplayName("password change without currentPassword -> IllegalArgumentException")
        void updateProfilePasswordWithoutCurrent() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.updateProfile(1L, Map.of("password", "newpass1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("password corrente");
        }

        @Test
        @DisplayName("password change with wrong currentPassword -> IllegalArgumentException")
        void updateProfilePasswordWrongCurrent() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("nope", "hashed-password")).thenReturn(false);

            assertThatThrownBy(() -> userService.updateProfile(1L,
                    Map.of("password", "newpass1", "currentPassword", "nope")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("corrente non è corretta");
        }

        @Test
        @DisplayName("password change to < 6 chars -> IllegalArgumentException")
        void updateProfilePasswordTooShort() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("plain-password", "hashed-password")).thenReturn(true);

            assertThatThrownBy(() -> userService.updateProfile(1L,
                    Map.of("password", "abc", "currentPassword", "plain-password")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("almeno 6 caratteri");
        }

        @Test
        @DisplayName("password change success -> re-encoded and saved")
        void updateProfilePasswordSuccess() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("plain-password", "hashed-password")).thenReturn(true);
            when(passwordEncoder.encode("newpass1")).thenReturn("hashed-new");
            when(userRepository.save(user)).thenReturn(user);

            userService.updateProfile(1L,
                    Map.of("password", "newpass1", "currentPassword", "plain-password"));

            assertThat(user.getPassword()).isEqualTo("hashed-new");
            verify(passwordEncoder).encode("newpass1");
        }

        @Test
        @DisplayName("empty updates -> no changes, still saved")
        void updateProfileNoop() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);

            userService.updateProfile(1L, Map.of());

            assertThat(user.getEmail()).isEqualTo("carlo@test.local");
            assertThat(user.getPassword()).isEqualTo("hashed-password");
        }
    }
}
