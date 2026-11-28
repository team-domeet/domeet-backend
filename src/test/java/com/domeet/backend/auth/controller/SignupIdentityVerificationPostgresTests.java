package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.service.PortOneIdentityVerificationClient;
import com.domeet.backend.auth.service.PortOneIdentityVerificationClient.TestChannel;
import com.domeet.backend.auth.service.PortOneIdentityVerificationClient.VerifiedFields;
import com.domeet.backend.auth.service.SignupIdentityVerificationService;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class SignupIdentityVerificationPostgresTests {

	private static final String PATH = "/api/v1/auth/signup/identity-verifications";
	private final SignupTokenIssuer tokens = new SignupTokenIssuer(Base64.getEncoder().encodeToString(new byte[32]));
	private final PortOneIdentityVerificationClient portOne = mock(PortOneIdentityVerificationClient.class);

	@Autowired
	private JdbcClient jdbc;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		when(portOne.testChannel()).thenReturn(Optional.of(new TestChannel("test-store", "test-channel")));
		var service = new SignupIdentityVerificationService(jdbc, portOne);
		mvc = MockMvcBuilders.standaloneSetup(new SignupIdentityVerificationController(tokens, service)).build();
	}

	@Test
	void rejectsMissingTokenAndSignupWithoutCompletedTerms() throws Exception {
		mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
		start(tokens.issueForGoogle(UUID.randomUUID().toString())).andExpect(status().isConflict());
	}

	@Test
	void expirationMatchesSignupTokenAndOtherMembersCannotConfirm() throws Exception {
		String owner = createSignup();
		String other = createSignup();
		var body = startAttempt(owner);
		Instant tokenExpiry = SignedJWT.parse(owner).getJWTClaimsSet().getExpirationTime().toInstant();
		assertEquals(tokenExpiry, Instant.parse(body.get("expiresAt").asString()));
		assertEquals("test-store", body.get("storeId").asString());
		assertEquals("test-channel", body.get("channelKey").asString());

		confirm(other, UUID.fromString(body.get("identityVerificationId").asString()))
				.andExpect(status().isNotFound());
	}

	@Test
	void incompleteResultDoesNotConfirmAndVerifiedResultIsReused() throws Exception {
		String token = createSignup();
		UUID id = UUID.fromString(startAttempt(token).get("identityVerificationId").asString());
		when(portOne.findVerifiedFields(id)).thenReturn(Optional.empty(), Optional.of(new VerifiedFields(true, false)));
		confirm(token, id).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDENTITY_VERIFICATION_NOT_COMPLETED"));
		assertEquals(0, jdbc.sql("""
				SELECT count(*) FROM signup_identity_verification_attempts WHERE id = :id AND confirmed_at IS NOT NULL
				""").param("id", id).query(Integer.class).single());

		for (int retry = 0; retry < 2; retry++) {
			confirm(token, id).andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("VERIFIED"))
					.andExpect(jsonPath("$.birthDateAvailable").value(true))
					.andExpect(jsonPath("$.ciAvailable").value(false))
					.andExpect(jsonPath("$.birthDate").doesNotExist())
					.andExpect(jsonPath("$.ci").doesNotExist());
		}
		verify(portOne, times(2)).findVerifiedFields(id);
		assertEquals("IDENTITY_VERIFICATION", jdbc.sql("""
				SELECT p.step FROM signup_progress p
				JOIN signup_identity_verification_attempts a ON a.user_id = p.user_id WHERE a.id = :id
				""").param("id", id).query(String.class).single());
	}

	@Test
	void expiredAttemptCannotBeConfirmed() throws Exception {
		String token = createSignup();
		UUID id = UUID.fromString(startAttempt(token).get("identityVerificationId").asString());
		jdbc.sql("""
				UPDATE signup_identity_verification_attempts
				SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
				    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
				WHERE id = :id
				""").param("id", id).update();
		confirm(token, id).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDENTITY_VERIFICATION_ATTEMPT_EXPIRED"));
		verify(portOne, times(0)).findVerifiedFields(id);
	}

	private String createSignup() {
		String subject = UUID.randomUUID().toString();
		jdbc.sql("""
				WITH member AS (INSERT INTO users (status) VALUES ('PENDING') RETURNING id),
				account AS (
					INSERT INTO social_accounts (user_id, provider, provider_user_id)
					SELECT id, 'GOOGLE', :subject FROM member
				)
				INSERT INTO signup_progress (user_id, step, draft_data, updated_at, expires_at)
				SELECT id, 'IDENTITY_VERIFICATION', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days'
				FROM member
				""").param("subject", subject).update();
		return tokens.issueForGoogle(subject);
	}

	private JsonNode startAttempt(String token) throws Exception {
		return JsonMapper.shared().readTree(start(token).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());
	}

	private ResultActions start(String token) throws Exception {
		return mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private ResultActions confirm(String token, UUID id) throws Exception {
		return mvc.perform(post(PATH + "/{id}/confirm", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}
}
