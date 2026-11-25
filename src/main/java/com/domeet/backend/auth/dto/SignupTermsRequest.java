package com.domeet.backend.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SignupTermsRequest(@NotNull @Size(min = 2, max = 2) List<@NotNull @Valid Consent> consents) {

	public record Consent(@NotBlank String id, @NotBlank String version) {
	}
}
