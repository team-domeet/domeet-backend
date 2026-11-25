package com.domeet.backend.auth.controller;

import com.domeet.backend.auth.dto.SocialLoginRequest;
import com.domeet.backend.auth.dto.SignupRequiredResponse;
import com.domeet.backend.auth.service.GoogleIdTokenVerifier;
import com.domeet.backend.auth.service.SignupTokenIssuer;
import com.domeet.backend.auth.service.SocialLoginMemberLookup;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

	private final GoogleIdTokenVerifier googleIdTokenVerifier;
	private final SocialLoginMemberLookup memberLookup;
	private final SignupTokenIssuer signupTokenIssuer;

	public AuthController(GoogleIdTokenVerifier googleIdTokenVerifier, SocialLoginMemberLookup memberLookup,
			SignupTokenIssuer signupTokenIssuer) {
		this.googleIdTokenVerifier = googleIdTokenVerifier;
		this.memberLookup = memberLookup;
		this.signupTokenIssuer = signupTokenIssuer;
	}

	@PostMapping(path = "/api/v1/auth/social-logins", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<SignupRequiredResponse> login(@Valid @RequestBody SocialLoginRequest request) {
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
			if (memberLookup.findGoogleMember(subject).state() == SocialLoginMemberLookup.State.NO_LINKED_MEMBER) {
				return ResponseEntity.ok()
						.cacheControl(CacheControl.noStore())
						.body(new SignupRequiredResponse(signupTokenIssuer.issueForGoogle(subject)));
			}
		} catch (DataAccessException | JwtEncodingException exception) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}
}
