package com.domeet.backend.auth.service;

import java.util.Optional;

import com.domeet.backend.auth.repository.SocialAccountRepository;
import com.domeet.backend.auth.repository.SocialAccountRepository.LinkedMember;
import com.domeet.backend.auth.repository.SocialAccountRepository.MemberStatus;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.Result;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialLoginMemberLookupTests {

	private final SocialAccountRepository repository = mock(SocialAccountRepository.class);
	private final SocialLoginMemberLookup lookup = new SocialLoginMemberLookup(repository);

	@ParameterizedTest
	@CsvSource({
			"ACTIVE, EXISTING_MEMBER",
			"PENDING, SIGNUP_INCOMPLETE",
			"WITHDRAWN, WITHDRAWN_MEMBER"
	})
	void classifiesLinkedMember(MemberStatus status, State expectedState) {
		when(repository.findLinkedMember("GOOGLE", "google-subject"))
				.thenReturn(Optional.of(new LinkedMember(42L, status)));

		assertEquals(new Result(expectedState, 42L), lookup.findGoogleMember("google-subject"));
	}

	@Test
	void identifiesUnlinkedSocialAccount() {
		when(repository.findLinkedMember("GOOGLE", "google-subject")).thenReturn(Optional.empty());

		assertEquals(new Result(State.NO_LINKED_MEMBER, null), lookup.findGoogleMember("google-subject"));
	}
}
