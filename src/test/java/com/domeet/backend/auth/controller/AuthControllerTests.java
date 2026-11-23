package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.service.GoogleIdTokenVerifier;
import com.domeet.backend.auth.service.SocialLoginMemberLookup;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTests {

	private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
	private final SocialLoginMemberLookup memberLookup = mock(SocialLoginMemberLookup.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(verifier, memberLookup)).build();

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
	void verifiedTokenDoesNotIssueSessionYet() throws Exception {
		when(verifier.isConfigured()).thenReturn(true);
		when(verifier.verify("example")).thenReturn("google-subject");
		performGoogleLogin().andExpect(status().isNotImplemented());
		verify(memberLookup).findGoogleMember("google-subject");
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
		return mvc.perform(post("/api/v1/auth/social-logins")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"provider":"GOOGLE","idToken":"example"}
						"""));
	}
}
