package com.domeet.backend.auth.service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleIdTokenVerifierTests {

	private static final String CLIENT_ID = "domeet-test.apps.googleusercontent.com";
	private static final String ISSUER = "https://accounts.google.com";
	private static KeyPair keys;
	private static GoogleIdTokenVerifier verifier;

	@BeforeAll
	static void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		verifier = new GoogleIdTokenVerifier(CLIENT_ID,
				NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build());
	}

	@Test
	void returnsSubjectFromVerifiedToken() throws JOSEException {
		assertEquals("google-user", verifier.verify(token(ISSUER, CLIENT_ID, Instant.now().plusSeconds(300))));
		assertEquals("google-user",
				verifier.verify(token("accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(300))));
	}

	@Test
	void rejectsTokenForAnotherClient() throws JOSEException {
		String token = token(ISSUER, "other-client", Instant.now().plusSeconds(300));
		assertThrows(BadJwtException.class, () -> verifier.verify(token));
	}

	@Test
	void rejectsExpiredToken() throws JOSEException {
		String token = token(ISSUER, CLIENT_ID, Instant.now().minusSeconds(1));
		assertThrows(BadJwtException.class, () -> verifier.verify(token));
	}

	@Test
	void rejectsUnexpectedIssuer() throws JOSEException {
		String token = token("https://example.com", CLIENT_ID, Instant.now().plusSeconds(300));
		assertThrows(BadJwtException.class, () -> verifier.verify(token));
	}

	@Test
	void rejectsTokenWithUntrustedSignature() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String token = token(ISSUER, CLIENT_ID, Instant.now().plusSeconds(300), generator.generateKeyPair());
		assertThrows(BadJwtException.class, () -> verifier.verify(token));
	}

	private static String token(String issuer, String audience, Instant expiresAt) throws JOSEException {
		return token(issuer, audience, expiresAt, keys);
	}

	private static String token(String issuer, String audience, Instant expiresAt, KeyPair signingKeys)
			throws JOSEException {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.audience(audience)
				.subject("google-user")
				.expirationTime(Date.from(expiresAt))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) signingKeys.getPrivate()));
		return jwt.serialize();
	}
}
