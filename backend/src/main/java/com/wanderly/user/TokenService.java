package com.wanderly.user;

import com.wanderly.config.WanderlyProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final WanderlyProperties props;

    public TokenService(JwtEncoder encoder, WanderlyProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public AuthDtos.TokenResponse issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.security().tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("wanderly")
                .subject(user.getId().toString())
                .issuedAt(now)
                .claim(TokenRevocation.ISSUED_AT_MS, now.toEpochMilli())   // iat is whole seconds; revocation needs finer
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthDtos.TokenResponse(token, "Bearer", expiresAt, user.getId());
    }
}
