package com.domeet.backend.auth.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SocialAccountRepository {

	private final JdbcClient jdbcClient;

	public SocialAccountRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<LinkedMember> findLinkedMember(String provider, String providerUserId) {
		return jdbcClient.sql("""
				SELECT u.id, u.status
				FROM social_accounts AS sa
				JOIN users AS u ON u.id = sa.user_id
				WHERE sa.provider = :provider AND sa.provider_user_id = :providerUserId
				""")
				.param("provider", provider)
				.param("providerUserId", providerUserId)
				.query((row, rowNumber) -> new LinkedMember(
						row.getLong("id"), MemberStatus.valueOf(row.getString("status"))))
				.optional();
	}

	public record LinkedMember(long id, MemberStatus status) {
	}

	public enum MemberStatus {
		PENDING,
		ACTIVE,
		WITHDRAWN
	}
}
