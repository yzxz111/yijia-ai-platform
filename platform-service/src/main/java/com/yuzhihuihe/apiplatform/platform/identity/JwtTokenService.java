package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private final JwtEncoder encoder;
    private final String issuer;
    private final String keyId;

    public JwtTokenService(
            JwtEncoder encoder,
            RSAKey jwtRsaKey,
            @Value("${security.jwt.issuer:yuzhihuihe-platform}") String issuer) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.keyId = jwtRsaKey.getKeyID();
    }

    public String createAccessToken(IdentityService.UserProfile user) {
        return encode(user, null, null);
    }

    public String createAccessToken(
            IdentityService.UserProfile user, UUID terminalSessionId, String clientType) {
        if (terminalSessionId == null || !"DESKTOP_WEB".equals(clientType)) {
            throw new IllegalArgumentException("invalid_terminal_token_context");
        }
        return encode(user, terminalSessionId, clientType);
    }

    private String encode(
            IdentityService.UserProfile user, UUID terminalSessionId, String clientType) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ACCESS_TOKEN_TTL))
                .subject(user.id().toString())
                .id(UUID.randomUUID().toString())
                .claim("role", user.role())
                .claim("tenant_id", user.tenantId().toString())
                .claim("name", user.displayName())
                .claim("password_set", user.passwordSet())
                .claim("account_status", user.status())
                .claim("auth_version", user.authVersion());
        if (terminalSessionId != null) {
            builder.claim("session_id", terminalSessionId.toString())
                    .claim("client_type", clientType);
        }
        if (user.email() != null) {
            builder.claim("email", user.email());
        }
        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
