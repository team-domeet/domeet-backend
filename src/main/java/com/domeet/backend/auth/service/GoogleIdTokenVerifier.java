package com.domeet.backend.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GoogleIdTokenVerifier {

	private static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
	private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

	private final String clientId;
	private final NimbusJwtDecoder decoder;

	@Autowired
	public GoogleIdTokenVerifier(@Value("${DOMEET_GOOGLE_CLIENT_ID:}") String clientId) {
		this(clientId, NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build());
	}

	GoogleIdTokenVerifier(String clientId, NimbusJwtDecoder decoder) {
		this.clientId = clientId;
		this.decoder = decoder;

		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setAllowEmptyExpiryClaim(false);
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(timestamps, this::validateClaims)));
	}

	public boolean isConfigured() {
		return StringUtils.hasText(clientId);
	}

	public String verify(String idToken) {
		return decoder.decode(idToken).getSubject();
	}

	private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
		String issuer = jwt.getClaimAsString("iss");
		List<String> audience = jwt.getAudience();
		if (issuer != null && ISSUERS.contains(issuer) && isConfigured()
				&& audience != null && audience.contains(clientId) && StringUtils.hasText(jwt.getSubject())) {
			return OAuth2TokenValidatorResult.success();
		}
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
	}
}
