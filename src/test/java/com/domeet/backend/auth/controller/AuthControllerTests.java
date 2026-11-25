package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.service.GoogleIdTokenVerifier;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import com.domeet.backend.auth.service.SocialLoginMemberLookup;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.Result;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.State;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTests {

	private static final byte[] TEST_SIGNING_KEY = new byte[32];

	private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
	private final SocialLoginMemberLookup memberLookup = mock(SocialLoginMemberLookup.class);
	private final MockMvc mvc = buildMvc(Base64.getEncoder().encodeToString(TEST_SIGNING_KEY));

	@Test
	void rejectsBlankIdTokenBeforeVerifying() throws Exception {
		mvc.perform(post("/api/v1/auth/social-logins")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"provider":"GOOGLE","idToken":" "}
						"""))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(verifier);
	}

	@Test
	void issuesSignedSignupTokenForUnlinkedGoogleAccount() throws Exception {
		stubVerifiedGoogleMember(new Result(State.NO_LINKED_MEMBER, null));

		String body = performGoogleLogin()
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.result").value("SIGNUP_REQUIRED"))
				.andExpect(jsonPath("$.nextStep").value("TERMS"))
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		SignedJWT token = SignedJWT.parse(JsonMapper.shared().readTree(body).get("signupToken").asString());
		assertEquals(JWSAlgorithm.HS256, token.getHeader().getAlgorithm());
		assertEquals("signup+jwt", token.getHeader().getType().getType());
		assertTrue(token.verify(new MACVerifier(TEST_SIGNING_KEY)));
		var claims = token.getJWTClaimsSet();
		assertEquals("domeet", claims.getIssuer());
		assertEquals(List.of("domeet-signup"), claims.getAudience());
		assertEquals("google-subject", claims.getSubject());
		assertEquals("GOOGLE", claims.getStringClaim("provider"));
		assertEquals(Duration.ofMinutes(15),
				Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()));
	}

	@Test
	void returnsServiceUnavailableWithoutSignupSigningKey() throws Exception {
		stubVerifiedGoogleMember(new Result(State.NO_LINKED_MEMBER, null));

		performGoogleLogin(buildMvc("")).andExpect(status().isServiceUnavailable());
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = "NO_LINKED_MEMBER", mode = EnumSource.Mode.EXCLUDE)
	void linkedMemberDoesNotReceiveSignupToken(State state) throws Exception {
		stubVerifiedGoogleMember(new Result(state, 42L));

		performGoogleLogin().andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.signupToken").doesNotExist());
		verify(memberLookup).findGoogleMember("google-subject");
	}

	private void stubVerifiedGoogleMember(Result result) {
		when(verifier.isConfigured()).thenReturn(true);
		when(verifier.verify("example")).thenReturn("google-subject");
		when(memberLookup.findGoogleMember("google-subject")).thenReturn(result);
	}

	@Test
	void rejectsInvalidGoogleToken() throws Exception {
		when(verifier.isConfigured()).thenReturn(true);
		when(verifier.verify("example")).thenThrow(new BadJwtException("Invalid token"));
		performGoogleLogin().andExpect(status().isUnauthorized());
		verifyNoInteractions(memberLookup);
	}

	@Test
	void returnsServiceUnavailableWithoutClientId() throws Exception {
		performGoogleLogin().andExpect(status().isServiceUnavailable());
	}

	@Test
	void returnsServiceUnavailableWhenVerificationCannotRun() throws Exception {
		when(verifier.isConfigured()).thenReturn(true);
		when(verifier.verify("example")).thenThrow(new JwtException("Verification unavailable"));
		performGoogleLogin().andExpect(status().isServiceUnavailable());
	}

	@Test
	void returnsServiceUnavailableWhenMemberLookupFails() throws Exception {
		when(verifier.isConfigured()).thenReturn(true);
		when(verifier.verify("example")).thenReturn("google-subject");
		when(memberLookup.findGoogleMember("google-subject"))
				.thenThrow(new DataAccessResourceFailureException("Database unavailable"));
		performGoogleLogin().andExpect(status().isServiceUnavailable());
	}

	private ResultActions performGoogleLogin() throws Exception {
		return performGoogleLogin(mvc);
	}

	private MockMvc buildMvc(String signingKey) {
		return MockMvcBuilders.standaloneSetup(
				new AuthController(verifier, memberLookup, new SignupTokenIssuer(signingKey))).build();
	}

	private ResultActions performGoogleLogin(MockMvc client) throws Exception {
		return client.perform(post("/api/v1/auth/social-logins")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"provider":"GOOGLE","idToken":"example"}
						"""));
	}
}
