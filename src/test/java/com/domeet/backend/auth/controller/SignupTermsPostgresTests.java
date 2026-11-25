package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.service.SignupTermsService;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.domeet.backend.auth.dto.SignupTermsRequest.Consent;
import com.domeet.backend.auth.service.SignupTermsService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class SignupTermsPostgresTests {

	private static final String VALID_CONSENTS = """
			{"consents":[{"id":"service","version":"draft-1"},{"id":"privacy","version":"draft-1"}]}
			""";
	private static final byte[] SIGNING_KEY = new byte[32];
	private final String subject = UUID.randomUUID().toString();
	private final SignupTokenIssuer issuer = new SignupTokenIssuer(Base64.getEncoder().encodeToString(SIGNING_KEY));

	@Autowired
	private SignupTermsService service;
	@Autowired
	private JdbcClient jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new SignupTermsController(issuer, service, true)).build();
	}

	@Test
	void createsPendingMemberAndPreservesConsentOnRetry() throws Exception {
		String token = issuer.issueForGoogle(subject);
		submit(token, VALID_CONSENTS).andExpect(status().isOk())
				.andExpect(jsonPath("$.nextStep").value("IDENTITY_VERIFICATION"));
		var saved = readProgress();
		assertEquals("PENDING", saved.get("status"));
		assertEquals("IDENTITY_VERIFICATION", saved.get("step"));
		assertEquals("draft-1", saved.get("service_version"));
		assertEquals("draft-1", saved.get("privacy_version"));
		assertNotNull(saved.get("accepted_at"));
		assertEquals(30, saved.get("retention_days"));

		submit(token, VALID_CONSENTS).andExpect(status().isOk());
		assertEquals(saved, readProgress());
	}

	@Test
	void rejectsMissingAndIncorrectConsentVersions() throws Exception {
		String token = issuer.issueForGoogle(subject);
		submit(token, """
				{"consents":[{"id":"service","version":"draft-1"}]}
				""").andExpect(status().isBadRequest());
		submit(token, VALID_CONSENTS.replace("draft-1", "stale")).andExpect(status().isBadRequest());
		submit(token, VALID_CONSENTS.replace("privacy", "service")).andExpect(status().isBadRequest());
		assertNoSocialAccount();
	}

	@Test
	void rejectsExpiredTokenAndTokenForAnotherAudience() throws Exception {
		SignedJWT issued = SignedJWT.parse(issuer.issueForGoogle(subject));
		Instant expiredAt = Instant.now().minusSeconds(60);
		submit(sign(issued, new JWTClaimsSet.Builder(issued.getJWTClaimsSet())
				.issueTime(Date.from(expiredAt.minusSeconds(900)))
				.expirationTime(Date.from(expiredAt)).build()), VALID_CONSENTS)
				.andExpect(status().isUnauthorized());
		submit(sign(issued, new JWTClaimsSet.Builder(issued.getJWTClaimsSet()).audience("domeet-api").build()),
				VALID_CONSENTS).andExpect(status().isUnauthorized());
		assertNoSocialAccount();
	}

	@Test
	void rejectsTokenWithInvalidSignature() throws Exception {
		byte[] otherKey = new byte[32];
		otherKey[0] = 1;
		var otherIssuer = new SignupTokenIssuer(Base64.getEncoder().encodeToString(otherKey));
		submit(otherIssuer.issueForGoogle(subject), VALID_CONSENTS).andExpect(status().isUnauthorized());
		assertNoSocialAccount();
	}

	@Test
	void rejectsAnActiveMemberWithoutChangingSignupProgress() throws Exception {
		jdbc.sql("""
				WITH member AS (INSERT INTO users (status) VALUES ('ACTIVE') RETURNING id)
				INSERT INTO social_accounts (user_id, provider, provider_user_id)
				SELECT id, 'GOOGLE', :subject FROM member
				""").param("subject", subject).update();

		submit(issuer.issueForGoogle(subject), VALID_CONSENTS).andExpect(status().isConflict());
		assertEquals(0, jdbc.sql("""
				SELECT count(*) FROM signup_progress p JOIN social_accounts a ON a.user_id = p.user_id
				WHERE a.provider = 'GOOGLE' AND a.provider_user_id = :subject
				""").param("subject", subject).query(Integer.class).single());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentSubmissionsReuseOneMemberAndOneConsent() throws Exception {
		var consents = List.of(new Consent("service", "draft-1"), new Consent("privacy", "draft-1"));
		var start = new CyclicBarrier(2);
		Callable<Outcome> submit = () -> {
			start.await(5, TimeUnit.SECONDS);
			return service.consentForGoogle(subject, consents);
		};
		try {
			try (var threads = Executors.newFixedThreadPool(2)) {
				var first = threads.submit(submit);
				var second = threads.submit(submit);
				assertEquals(Outcome.ACCEPTED, first.get(10, TimeUnit.SECONDS));
				assertEquals(Outcome.ACCEPTED, second.get(10, TimeUnit.SECONDS));
			}
			var saved = readProgress();
			assertEquals(Outcome.ACCEPTED, service.consentForGoogle(subject, consents));
			assertEquals(saved, readProgress());
		} finally {
			jdbc.sql("""
					WITH account AS (
					    DELETE FROM social_accounts WHERE provider = 'GOOGLE' AND provider_user_id = :subject
					    RETURNING user_id
					), progress AS (
					    DELETE FROM signup_progress WHERE user_id IN (SELECT user_id FROM account)
					)
					DELETE FROM users WHERE id IN (SELECT user_id FROM account)
					""").param("subject", subject).update();
		}
	}

	private ResultActions submit(String token, String body) throws Exception {
		return mvc.perform(post("/api/v1/auth/signup/terms-consents")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private Map<String, Object> readProgress() {
		return jdbc.sql("""
				SELECT u.id, u.status, p.step, p.draft_data -> 'terms' ->> 'service' AS service_version,
				       p.draft_data -> 'terms' ->> 'privacy' AS privacy_version,
				       p.draft_data ->> 'termsAcceptedAt' AS accepted_at, p.expires_at,
				       EXTRACT(DAY FROM p.expires_at - p.updated_at)::int AS retention_days
				FROM users u JOIN social_accounts a ON a.user_id = u.id
				JOIN signup_progress p ON p.user_id = u.id
				WHERE a.provider = 'GOOGLE' AND a.provider_user_id = :subject
				""").param("subject", subject).query().singleRow();
	}

	private void assertNoSocialAccount() {
		assertEquals(0, jdbc.sql("""
				SELECT count(*) FROM social_accounts WHERE provider = 'GOOGLE' AND provider_user_id = :subject
				""").param("subject", subject).query(Integer.class).single());
	}

	private String sign(SignedJWT issued, JWTClaimsSet claims) throws Exception {
		SignedJWT token = new SignedJWT(issued.getHeader(), claims);
		token.sign(new MACSigner(SIGNING_KEY));
		return token.serialize();
	}
}
