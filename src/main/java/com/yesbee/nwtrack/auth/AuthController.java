package com.yesbee.nwtrack.auth;

import com.yesbee.nwtrack.config.NwTrackProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Local user authentication and JWT issuance")
public class AuthController {
    private final AuthenticationManager authenticationManager; private final JwtEncoder encoder; private final NwTrackProperties properties; private final AccessPolicy access;
    public AuthController(AuthenticationManager authenticationManager, JwtEncoder encoder, NwTrackProperties properties,AccessPolicy access) { this.authenticationManager=authenticationManager; this.encoder=encoder; this.properties=properties; this.access=access; }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Authenticates an active local user and returns a bearer JWT")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        access.channel(auth.getName(),request.clientType());
        Instant now = Instant.now(); Instant expires = now.plus(properties.security().accessTokenTtl());
        List<String> roles = auth.getAuthorities().stream().map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).toList();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.security().issuer()).issuedAt(now).expiresAt(expires).subject(auth.getName()).claim("roles", roles).claim("clientType",request.clientType()).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.security().accessTokenTtl().toSeconds(), auth.getName(), roles);
    }
    public record LoginRequest(@NotBlank String username, @NotBlank String password,@NotBlank @jakarta.validation.constraints.Pattern(regexp="PC|PAD") String clientType) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String username, List<String> roles) {}
}
