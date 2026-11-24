package com.domeet.backend.auth.service;

import com.domeet.backend.auth.service.SocialLoginMemberLookup.Result;
import com.domeet.backend.auth.service.SocialLoginMemberLookup.State;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class SocialLoginMemberLookupPostgresTests {

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private SocialLoginMemberLookup memberLookup;

	@ParameterizedTest
	@CsvSource({
			"ACTIVE, EXISTING_MEMBER",
			"PENDING, SIGNUP_INCOMPLETE",
			"WITHDRAWN, WITHDRAWN_MEMBER"
	})
	void findsAndClassifiesGoogleMember(String status, State state) {
		String subject = UUID.randomUUID().toString();
		long memberId = insertUser(status);
		linkAccount(memberId, "GOOGLE", subject);
		linkAccount(insertUser("ACTIVE"), "KAKAO", subject);

		assertEquals(new Result(state, memberId), memberLookup.findGoogleMember(subject));
	}

	@Test
	void doesNotMatchAnAccountFromAnotherProvider() {
		String subject = UUID.randomUUID().toString();
		linkAccount(insertUser("ACTIVE"), "KAKAO", subject);

		assertEquals(new Result(State.NO_LINKED_MEMBER, null), memberLookup.findGoogleMember(subject));
	}

	@Test
	void returnsNoLinkedMemberForUnknownSubject() {
		assertEquals(new Result(State.NO_LINKED_MEMBER, null),
				memberLookup.findGoogleMember(UUID.randomUUID().toString()));
	}

	@Test
	void rejectsSocialAccountWithoutMember() {
		long memberId = insertUser("PENDING");
		jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", memberId).update();

		assertSqlState("23503", () -> linkAccount(memberId, "GOOGLE", UUID.randomUUID().toString()));
	}

	@Test
	void rejectsLinkingSameProviderAccountToAnotherMember() {
		String subject = UUID.randomUUID().toString();
		linkAccount(insertUser("ACTIVE"), "GOOGLE", subject);
		long otherMemberId = insertUser("PENDING");

		assertSqlState("23505", () -> linkAccount(otherMemberId, "GOOGLE", subject));
	}

	@Test
	void rejectsLinkingSecondAccountFromSameProviderToMember() {
		long memberId = insertUser("ACTIVE");
		linkAccount(memberId, "GOOGLE", UUID.randomUUID().toString());

		assertSqlState("23505", () -> linkAccount(memberId, "GOOGLE", UUID.randomUUID().toString()));
	}

	@Test
	void rejectsUnknownMemberStatus() {
		assertSqlState("23514", () -> insertUser("UNKNOWN"));
	}

	@Test
	void rejectsUnknownProvider() {
		long memberId = insertUser("PENDING");

		assertSqlState("23514", () -> linkAccount(memberId, "UNKNOWN", UUID.randomUUID().toString()));
	}

	private long insertUser(String status) {
		return jdbcClient.sql("INSERT INTO users (status) VALUES (:status) RETURNING id")
				.param("status", status)
				.query(Long.class)
				.single();
	}

	private void linkAccount(long memberId, String provider, String subject) {
		jdbcClient.sql("""
				INSERT INTO social_accounts (user_id, provider, provider_user_id)
				VALUES (:memberId, :provider, :subject)
				""")
				.param("memberId", memberId)
				.param("provider", provider)
				.param("subject", subject)
				.update();
	}

	private void assertSqlState(String expected, Executable operation) {
		var exception = assertThrows(DataIntegrityViolationException.class, operation);
		assertEquals(expected, ((SQLException) exception.getMostSpecificCause()).getSQLState());
	}
}
