package com.clinicemr.shared.security;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public static final String STAFF_ISSUER = "clinic-emr/staff";

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    public TokenService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public IssuedToken issueStaffToken(long staffId, String email, String name) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(STAFF_ISSUER)
                .subject(String.valueOf(staffId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", email)
                .claim("name", name)
                .claim("typ", "staff")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public static final String PATIENT_ISSUER = "clinic-emr/patient";

    public IssuedToken issuePatientToken(long accountId, String phoneE164) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(java.time.Duration.ofDays(30));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(PATIENT_ISSUER)
                .subject(String.valueOf(accountId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("phone", phoneE164)
                .claim("typ", "patient")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
