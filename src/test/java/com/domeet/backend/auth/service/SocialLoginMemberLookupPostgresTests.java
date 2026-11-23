package com.domeet.backend.auth.service;

import com.domeet.backend.auth.service.SocialLoginMemberLookup.Result;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class SocialLoginMemberLookupPostgresTests {

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private SocialLoginMemberLookup memberLookup;

	@BeforeEach
	void prepareTemporaryTables() {
		jdbcClient.sql("""
				CREATE TEMP TABLE users (
				    id BIGINT PRIMARY KEY,
				    status VARCHAR(20) NOT NULL
				) ON COMMIT DROP
				""").update();
		jdbcClient.sql("""
				CREATE TEMP TABLE social_accounts (
				    user_id BIGINT NOT NULL REFERENCES pg_temp.users(id),
				    provider VARCHAR(20) NOT NULL,
				    provider_user_id VARCHAR(255) NOT NULL,
				    UNIQUE (provider, provider_user_id),
				    UNIQUE (user_id, provider)
				) ON COMMIT DROP
				""").update();
		jdbcClient.sql("""
				INSERT INTO pg_temp.users (id, status) VALUES
				    (42, 'ACTIVE'), (43, 'PENDING'), (44, 'WITHDRAWN'), (45, 'ACTIVE'), (46, 'ACTIVE')
				""").update();
		jdbcClient.sql("""
				INSERT INTO pg_temp.social_accounts (user_id, provider, provider_user_id) VALUES
				    (42, 'GOOGLE', 'active-subject'),
				    (43, 'GOOGLE', 'pending-subject'),
				    (44, 'GOOGLE', 'withdrawn-subject'),
				    (45, 'KAKAO', 'active-subject'),
				    (46, 'KAKAO', 'kakao-only-subject')
				""").update();
	}

	@ParameterizedTest
	@CsvSource({
			"active-subject, EXISTING_MEMBER, 42",
			"pending-subject, SIGNUP_INCOMPLETE, 43",
			"withdrawn-subject, WITHDRAWN_MEMBER, 44"
	})
	void findsAndClassifiesGoogleMember(String subject, State state, Long memberId) {
		assertEquals(new Result(state, memberId), memberLookup.findGoogleMember(subject));
	}

	@Test
	void doesNotMatchAnAccountFromAnotherProvider() {
		assertEquals(new Result(State.NO_LINKED_MEMBER, null), memberLookup.findGoogleMember("kakao-only-subject"));
	}

	@Test
	void returnsNoLinkedMemberForUnknownSubject() {
		assertEquals(new Result(State.NO_LINKED_MEMBER, null), memberLookup.findGoogleMember("unknown-subject"));
	}
}
