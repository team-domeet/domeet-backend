package com.domeet.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SocialLoginRequest(
		@NotNull Provider provider,
		@NotBlank String idToken,
		String authorizationCode
) {
	public enum Provider {
		KAKAO,
		GOOGLE,
		APPLE
	}

	@Override
	public String toString() {
		return "SocialLoginRequest[provider=" + provider + "]";
	}
}
