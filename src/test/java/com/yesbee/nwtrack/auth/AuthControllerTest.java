package com.yesbee.nwtrack.auth;

import com.yesbee.nwtrack.config.NwTrackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    @Test
    void authenticatesAndReturnsBearerTokenWithRoles() {
        var manager = mock(AuthenticationManager.class);
        var encoder = mock(JwtEncoder.class);
        var authentication = mock(Authentication.class);
        when(manager.authenticate(any())).thenReturn(authentication);
        when(authentication.getName()).thenReturn("operator1");
        when(authentication.getAuthorities()).thenAnswer(ignored ->
                List.of(new SimpleGrantedAuthority("ROLE_PROCESS_MANAGER")));
        when(encoder.encode(any())).thenReturn(Jwt.withTokenValue("signed.jwt")
                .header("alg", "HS256").subject("operator1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
        var security = new NwTrackProperties.Security("secret", "nwtrack", Duration.ofMinutes(15), "admin", "password");
        var properties = new NwTrackProperties("Factory", security,
                new NwTrackProperties.Transport("simulator", Duration.ofSeconds(1)));

        var response = new AuthController(manager, encoder, properties,mock(AccessPolicy.class),mock(com.yesbee.nwtrack.users.ProcessAssignmentService.class))
                .login(new AuthController.LoginRequest("operator1", "password","PAD"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.roles()).containsExactly("PROCESS_MANAGER");
    }
}
