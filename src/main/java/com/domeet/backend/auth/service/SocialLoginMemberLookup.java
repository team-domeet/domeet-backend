package com.domeet.backend.auth.service;

import com.domeet.backend.auth.repository.SocialAccountRepository;
import org.springframework.stereotype.Service;

@Service
public class SocialLoginMemberLookup {

	private final SocialAccountRepository socialAccounts;

	public SocialLoginMemberLookup(SocialAccountRepository socialAccounts) {
		this.socialAccounts = socialAccounts;
	}

	public Result findGoogleMember(String verifiedSubject) {
		return socialAccounts.findLinkedMember("GOOGLE", verifiedSubject)
				.map(member -> new Result(switch (member.status()) {
					case ACTIVE -> State.EXISTING_MEMBER;
					case PENDING -> State.SIGNUP_INCOMPLETE;
					case WITHDRAWN -> State.WITHDRAWN_MEMBER;
				}, member.id()))
				.orElseGet(() -> new Result(State.NO_LINKED_MEMBER, null));
	}

	public record Result(State state, Long memberId) {
	}

	public enum State {
		EXISTING_MEMBER,
		SIGNUP_INCOMPLETE,
		WITHDRAWN_MEMBER,
		NO_LINKED_MEMBER
	}
}
