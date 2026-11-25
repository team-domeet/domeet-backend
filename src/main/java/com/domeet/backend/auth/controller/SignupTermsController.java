package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.dto.SignupTermsRequest;
import com.domeet.backend.auth.service.SignupTermsService;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SignupTermsController {

	private final SignupTokenIssuer signupTokens;
	private final SignupTermsService signupTerms;
	private final boolean draftTermsEnabled;

	public SignupTermsController(SignupTokenIssuer signupTokens, SignupTermsService signupTerms,
			@Value("${DOMEET_DRAFT_TERMS_ENABLED:false}") boolean draftTermsEnabled) {
		this.signupTokens = signupTokens;
		this.signupTerms = signupTerms;
		this.draftTermsEnabled = draftTermsEnabled;
	}

	@PostMapping(path = "/api/v1/auth/signup/terms-consents", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, String>> consent(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@Valid @RequestBody SignupTermsRequest request) {
		if (!draftTermsEnabled) {
			return error(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_TERMS_UNAVAILABLE");
		}
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return invalidToken();
		}
		try {
			String subject = signupTokens.verifyGoogleSubject(authorization.substring(7));
			return switch (signupTerms.consentForGoogle(subject, request.consents())) {
				case ACCEPTED -> ResponseEntity.ok(Map.of("nextStep", "IDENTITY_VERIFICATION"));
				case INVALID_CONSENTS -> error(HttpStatus.BAD_REQUEST, "INVALID_TERMS_CONSENTS");
				case CONFLICT -> error(HttpStatus.CONFLICT, "SIGNUP_STATE_CONFLICT");
			};
		} catch (BadJwtException exception) {
			return invalidToken();
		} catch (JwtEncodingException | DataAccessException | TransactionException exception) {
			return error(HttpStatus.SERVICE_UNAVAILABLE, "SIGNUP_TERMS_UNAVAILABLE");
		}
	}

	private ResponseEntity<Map<String, String>> invalidToken() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(Map.of("code", "INVALID_SIGNUP_TOKEN"));
	}

	private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
		return ResponseEntity.status(status).body(Map.of("code", code));
	}
}
