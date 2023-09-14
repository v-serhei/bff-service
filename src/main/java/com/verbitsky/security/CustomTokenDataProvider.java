package com.verbitsky.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class CustomTokenDataProvider implements TokenDataProvider {
    private static final JwsHeader DEFAULT_JWS_HEADER = JwsHeader.with(SignatureAlgorithm.RS256).build();
    private static final String ACCOUNT_KEY = "account";
    private static final String RESOURCE_KEY = "resource_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";
    private final ObjectMapper objectMapper;

    public CustomTokenDataProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SimpleGrantedAuthority> getGrantedAuthorities(String token)
            throws ParseException, IOException {

        var accountRoles = getAccountRolesFromToken(token);
        return buildAuthoritiesFromRoles(accountRoles);
    }

    @Override
    public JwtEncoderParameters getParametersFromToken(String token) throws ParseException {
        var claimsSet = buildClaimSet(SignedJWT.parse(token).getJWTClaimsSet().getClaims());
        var jwsHeader = buildJwsHeader(SignedJWT.parse(token).getHeader());

        return JwtEncoderParameters.from(jwsHeader, claimsSet);
    }

    @Override
    public Jwt buildJwt(String tokenValue, JwtEncoderParameters parameters) throws JwtEncodingException {
        var issuedAt = parameters.getClaims().getIssuedAt();
        var expiresAt = parameters.getClaims().getExpiresAt();

        return new Jwt(tokenValue,
                issuedAt,
                expiresAt,
                getHeaderAsMap(parameters.getJwsHeader()),
                parameters.getClaims().getClaims());
    }

    @Override
    public boolean isTokenValid(Jwt token) {
        var expiresAt = token.getExpiresAt();
        return Objects.nonNull(expiresAt) && expiresAt.isAfter(Instant.now());
    }

    @Override
    public boolean isTokenValid(String token) {
        Map<String, Object> payload;
        try {
            payload = SignedJWT.parse(token).getPayload().toJSONObject();
        } catch (ParseException e) {
            log.error("Token parsing error. Token value {}, error: {}", token, e.getMessage());
            return false;
        }
        var expiresAt = Instant.ofEpochSecond(Long.parseLong(payload.get(JwtClaimNames.EXP).toString()));
        return expiresAt.isAfter(Instant.now());
    }


    private JwsHeader buildJwsHeader(JWSHeader header) {
        if (header.toJSONObject().isEmpty()) {
            return DEFAULT_JWS_HEADER;
        }

        return JwsHeader
                .with(SignatureAlgorithm.from(header.getAlgorithm().getName()))
                .keyId(header.getKeyID())
                .type(header.getType().getType())
                .build();
    }

    private JwtClaimsSet buildClaimSet(Map<String, Object> claims) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
        claims.forEach(builder::claim);
        return builder.build();
    }


    private Map<String, Object> getHeaderAsMap(JwsHeader jwsHeader) {
        Map<String, Object> result = new HashMap<>();

        if (Objects.nonNull(jwsHeader)) {
            result.putAll(jwsHeader.getHeaders());
        } else {
            result.putAll(DEFAULT_JWS_HEADER.getHeaders());
        }

        return result;
    }

    private List<String> getAccountRolesFromToken(String token) throws IOException, ParseException {
        List<String> result = new ArrayList<>();
        JsonNode rolesNode = objectMapper
                .readTree(SignedJWT.parse(token).getPayload().toString())
                .path(RESOURCE_KEY)
                .get(ACCOUNT_KEY)
                .get(ROLES_KEY);

        if (rolesNode.isEmpty()) {
            return result;
        } else {
            List<String> roles = objectMapper
                    .readerForListOf(String.class)
                    .readValue(rolesNode);

            result.addAll(roles);
        }

        return result;
    }

    private List<SimpleGrantedAuthority> buildAuthoritiesFromRoles(List<String> roles) {
        return roles.stream()
                .map(String::toUpperCase)
                .map(ROLE_PREFIX::concat)
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
