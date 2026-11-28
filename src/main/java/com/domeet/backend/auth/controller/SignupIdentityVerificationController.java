package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.service.PortOneIdentityVerificationClient.LookupUnavailable;
import com.domeet.backend.auth.service.SignupIdentityVerificationService;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.AttemptExpired;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.AttemptNotFound;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.Confirmation;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.SignupStateConflict;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.StartedAttempt;
import com.domeet.backend.auth.service.SignupIdentityVerificationService.VerificationNotCompleted;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import com.domeet.backend.auth.service.SignupTokenIssuer.VerifiedGoogleToken;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/signup/identity-verifications")
public class SignupIdentityVerificationController {

	private final SignupTokenIssuer tokens;
	private final SignupIdentityVerificationService service;

	public SignupIdentityVerificationController(SignupTokenIssuer tokens, SignupIdentityVerificationService service) {
		this.tokens = tokens;
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<StartedAttempt> start(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(service.start(verifyToken(authorization)));
	}

	@PostMapping("/{id}/confirm")
	public ResponseEntity<Confirmation> confirm(@PathVariable("id") UUID id,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.confirm(verifyToken(authorization), id));
	}

	private VerifiedGoogleToken verifyToken(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			throw new BadJwtException("Invalid signup token");
		}
		return tokens.verifyGoogleToken(authorization.substring(7));
	}

	@ExceptionHandler(BadJwtException.class)
	ResponseEntity<Map<String, String>> invalidToken() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(Map.of("code", "INVALID_SIGNUP_TOKEN"));
	}

	@ExceptionHandler(AttemptNotFound.class)
	ResponseEntity<Map<String, String>> missingAttempt() {
		return error(HttpStatus.NOT_FOUND, "IDENTITY_VERIFICATION_ATTEMPT_NOT_FOUND");
	}

	@ExceptionHandler(AttemptExpired.class)
	ResponseEntity<Map<String, String>> expiredAttempt() {
		return error(HttpStatus.CONFLICT, "IDENTITY_VERIFICATION_ATTEMPT_EXPIRED");
	}

	@ExceptionHandler(SignupStateConflict.class)
	ResponseEntity<Map<String, String>> wrongSignupState() {
		return error(HttpStatus.CONFLICT, "SIGNUP_STATE_CONFLICT");
	}

	@ExceptionHandler(VerificationNotCompleted.class)
	ResponseEntity<Map<String, String>> unverified() {
		return error(HttpStatus.CONFLICT, "IDENTITY_VERIFICATION_NOT_COMPLETED");
	}

	@ExceptionHandler({LookupUnavailable.class, DataAccessException.class, JwtEncodingException.class})
	ResponseEntity<Map<String, String>> unavailable() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_VERIFICATION_UNAVAILABLE");
	}

	private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
		return ResponseEntity.status(status).body(Map.of("code", code));
	}
}
