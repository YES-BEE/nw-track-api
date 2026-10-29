package com.yesbee.nwtrack.auth;

import com.yesbee.nwtrack.config.NwTrackProperties;
import com.yesbee.nwtrack.shared.ActorResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@RestController @RequestMapping("/api/v1/auth") @Tag(name="Authentication sessions")
public class SessionController {
    private final JdbcTemplate jdbc;private final ActorResolver actors;private final PasswordEncoder passwords;private final JwtEncoder encoder;private final NwTrackProperties properties;
    public SessionController(JdbcTemplate jdbc,ActorResolver actors,PasswordEncoder passwords,JwtEncoder encoder,NwTrackProperties properties){this.jdbc=jdbc;this.actors=actors;this.passwords=passwords;this.encoder=encoder;this.properties=properties;}
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Jwt jwt){return jdbc.queryForMap("select u.id,u.username,u.display_name,u.enabled,u.must_change_password,usa.station_id,coalesce(usa.active_on_shift,false) active_on_shift from users u left join user_station_assignments usa on usa.user_id=u.id where lower(u.username)=lower(?)",jwt.getSubject());}
    @PostMapping("/change-password") public Map<String,Object> password(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PasswordChange r){UUID id=actors.id(jwt);String current=jdbc.queryForObject("select password_hash from users where id=?",String.class,id);if(!passwords.matches(r.currentPassword(),current))throw new org.springframework.security.authentication.BadCredentialsException("Current password is incorrect");jdbc.update("update users set password_hash=?,must_change_password=false,updated_at=now() where id=?",passwords.encode(r.newPassword()),id);jdbc.update("update refresh_tokens set revoked_at=now() where user_id=? and revoked_at is null",id);return Map.of("changed",true);}
    @PostMapping("/refresh-tokens") public RefreshResponse createRefresh(@AuthenticationPrincipal Jwt jwt){UUID userId=actors.id(jwt);String raw=randomToken();UUID id=UUID.randomUUID();jdbc.update("insert into refresh_tokens(id,user_id,token_hash,expires_at) values (?,?,?,now()+interval '7 days')",id,userId,hash(raw));return new RefreshResponse(raw,OffsetDateTime.now().plusDays(7));}
    @PostMapping("/refresh") public AuthController.TokenResponse refresh(@Valid @RequestBody RefreshRequest r){List<SessionRow> sessions=jdbc.query("select rt.id,u.id,u.username from refresh_tokens rt join users u on u.id=rt.user_id where rt.token_hash=? and rt.revoked_at is null and rt.expires_at>now() and u.enabled",(rs,n)->new SessionRow((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getString(3)),hash(r.refreshToken()));if(sessions.isEmpty())throw new org.springframework.security.authentication.BadCredentialsException("Refresh token is invalid or expired");SessionRow session=sessions.getFirst();List<String> roles=jdbc.query("select r.code from roles r join user_roles ur on ur.role_id=r.id where ur.user_id=?",(rs,n)->rs.getString(1),session.userId());Instant now=Instant.now(),expires=now.plus(properties.security().accessTokenTtl());JwtClaimsSet claims=JwtClaimsSet.builder().issuer(properties.security().issuer()).issuedAt(now).expiresAt(expires).subject(session.username()).claim("roles",roles).build();String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();return new AuthController.TokenResponse(token,"Bearer",properties.security().accessTokenTtl().toSeconds(),session.username(),roles);}
    @GetMapping("/sessions") public List<Map<String,Object>> sessions(@AuthenticationPrincipal Jwt jwt){return jdbc.queryForList("select id,created_at,expires_at,revoked_at from refresh_tokens where user_id=? order by created_at desc",actors.id(jwt));}
    @DeleteMapping("/sessions/{id}") public void revoke(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){jdbc.update("update refresh_tokens set revoked_at=now() where id=? and user_id=? and revoked_at is null",id,actors.id(jwt));}
    @PostMapping("/logout") public void logout(@RequestBody RefreshRequest r){jdbc.update("update refresh_tokens set revoked_at=now() where token_hash=? and revoked_at is null",hash(r.refreshToken()));}
    private String randomToken(){byte[] bytes=new byte[48];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record PasswordChange(@NotBlank String currentPassword,@NotBlank String newPassword){} public record RefreshRequest(@NotBlank String refreshToken){} public record RefreshResponse(String refreshToken,OffsetDateTime expiresAt){} private record SessionRow(UUID id,UUID userId,String username){}
}
