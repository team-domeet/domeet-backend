package com.domeet.backend.auth.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class SignupTokenIssuer {

	private static final Duration LIFETIME = Duration.ofMinutes(15);
	private static final String ISSUER = "domeet";
	private static final String AUDIENCE = "domeet-signup";
	private static final String PROVIDER = "GOOGLE";
	private static final JWSHeader HEADER = new JWSHeader.Builder(JWSAlgorithm.HS256)
			.type(new JOSEObjectType("signup+jwt"))
			.build();

	private final byte[] signingKey;

	public SignupTokenIssuer(@Value("${DOMEET_SIGNUP_TOKEN_SECRET:}") String encodedKey) {
		try {
			signingKey = Base64.getDecoder().decode(encodedKey);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("DOMEET_SIGNUP_TOKEN_SECRET must be Base64 encoded");
		}
		if (signingKey.length > 0 && signingKey.length < 32) {
			throw new IllegalArgumentException("DOMEET_SIGNUP_TOKEN_SECRET must contain at least 32 decoded bytes");
		}
	}

	public String issueForGoogle(String verifiedSubject) {
		if (signingKey.length == 0) {
			throw new JwtEncodingException("Signup token signing key is not configured");
		}
		Instant issuedAt = Instant.now();
		SignedJWT token = new SignedJWT(HEADER, new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.audience(AUDIENCE)
				.subject(verifiedSubject)
				.claim("provider", PROVIDER)
				.issueTime(Date.from(issuedAt))
				.expirationTime(Date.from(issuedAt.plus(LIFETIME)))
				.build());
		try {
			token.sign(new MACSigner(signingKey));
			return token.serialize();
		} catch (JOSEException exception) {
			throw new JwtEncodingException("Could not sign signup token", exception);
		}
	}

	public String verifyGoogleSubject(String value) {
		if (signingKey.length == 0) {
			throw new JwtEncodingException("Signup token signing key is not configured");
		}
		try {
			SignedJWT token = SignedJWT.parse(value);
			if (!HEADER.getAlgorithm().equals(token.getHeader().getAlgorithm())
					|| !HEADER.getType().equals(token.getHeader().getType())
					|| !token.verify(new MACVerifier(signingKey))) {
				throw new BadJwtException("Invalid signup token");
			}
			JWTClaimsSet claims = token.getJWTClaimsSet();
			Instant now = Instant.now();
			if (!ISSUER.equals(claims.getIssuer()) || !List.of(AUDIENCE).equals(claims.getAudience())
					|| !PROVIDER.equals(claims.getStringClaim("provider"))
					|| !StringUtils.hasText(claims.getSubject()) || claims.getSubject().length() > 255
					|| claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now)
					|| claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)
					|| !claims.getExpirationTime().toInstant().equals(claims.getIssueTime().toInstant().plus(LIFETIME))
					|| (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now))) {
				throw new BadJwtException("Invalid signup token");
			}
			return claims.getSubject();
		} catch (ParseException | JOSEException exception) {
			throw new BadJwtException("Invalid signup token");
		}
	}
}
