package com.domeet.backend.auth.dto;

public record SignupRequiredResponse(String result, String signupToken, String nextStep) {

	public SignupRequiredResponse(String signupToken) {
		this("SIGNUP_REQUIRED", signupToken, "TERMS");
	}

	@Override
	public String toString() {
		return "SignupRequiredResponse[result=" + result + ", nextStep=" + nextStep + "]";
	}
}
