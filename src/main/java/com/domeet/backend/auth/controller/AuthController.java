package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.dto.SocialLoginRequest;
import com.domeet.backend.auth.service.GoogleIdTokenVerifier;
import com.domeet.backend.auth.service.SocialLoginMemberLookup;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

	private final GoogleIdTokenVerifier googleIdTokenVerifier;
	private final SocialLoginMemberLookup memberLookup;

	public AuthController(GoogleIdTokenVerifier googleIdTokenVerifier, SocialLoginMemberLookup memberLookup) {
		this.googleIdTokenVerifier = googleIdTokenVerifier;
		this.memberLookup = memberLookup;
	}

	@PostMapping(path = "/api/v1/auth/social-logins", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> login(@Valid @RequestBody SocialLoginRequest request) {
		if (request.provider() != SocialLoginRequest.Provider.GOOGLE) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
		}
		if (!googleIdTokenVerifier.isConfigured()) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		String subject;
		try {
			subject = googleIdTokenVerifier.verify(request.idToken());
		} catch (BadJwtException exception) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (JwtException exception) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		try {
			memberLookup.findGoogleMember(subject);
		} catch (DataAccessException exception) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
