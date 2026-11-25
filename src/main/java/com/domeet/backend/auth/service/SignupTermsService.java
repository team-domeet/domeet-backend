package com.domeet.backend.auth.service;

import com.domeet.backend.auth.dto.SignupTermsRequest.Consent;
import com.domeet.backend.auth.repository.SocialAccountRepository;
import com.domeet.backend.auth.repository.SocialAccountRepository.MemberStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupTermsService {

	private static final String DRAFT_VERSION = "draft-1";
	private static final Set<Consent> REQUIRED_CONSENTS =
			Set.of(new Consent("service", DRAFT_VERSION), new Consent("privacy", DRAFT_VERSION));

	private final JdbcClient jdbcClient;
	private final SocialAccountRepository socialAccounts;

	public SignupTermsService(JdbcClient jdbcClient, SocialAccountRepository socialAccounts) {
		this.jdbcClient = jdbcClient;
		this.socialAccounts = socialAccounts;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Outcome consentForGoogle(String subject, List<Consent> consents) {
		if (consents.size() != REQUIRED_CONSENTS.size() || !REQUIRED_CONSENTS.equals(new HashSet<>(consents))) {
			return Outcome.INVALID_CONSENTS;
		}

		// Serialize first-time submissions before a user or social account row exists.
		jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:account, 0))")
				.param("account", "signup:GOOGLE:" + subject)
				.query((row, rowNumber) -> true)
				.single();

		var linkedMember = socialAccounts.findLinkedMember("GOOGLE", subject);
		if (linkedMember.isPresent()) {
			var member = linkedMember.get();
			boolean alreadyRecorded = member.status() == MemberStatus.PENDING && jdbcClient.sql("""
					SELECT EXISTS (
					    SELECT 1 FROM signup_progress
					    WHERE user_id = :userId AND step = 'IDENTITY_VERIFICATION'
					      AND expires_at > CURRENT_TIMESTAMP
					      AND draft_data -> 'terms' ->> 'service' = :service
					      AND draft_data -> 'terms' ->> 'privacy' = :privacy
					)
					""")
					.param("userId", member.id())
					.param("service", DRAFT_VERSION)
					.param("privacy", DRAFT_VERSION)
					.query(Boolean.class)
					.single();
			return alreadyRecorded ? Outcome.ACCEPTED : Outcome.CONFLICT;
		}

		long userId = jdbcClient.sql("INSERT INTO users (status) VALUES ('PENDING') RETURNING id")
				.query(Long.class)
				.single();
		jdbcClient.sql("""
				INSERT INTO social_accounts (user_id, provider, provider_user_id)
				VALUES (:userId, 'GOOGLE', :subject)
				""")
				.param("userId", userId)
				.param("subject", subject)
				.update();
		jdbcClient.sql("""
				INSERT INTO signup_progress (user_id, step, draft_data, updated_at, expires_at)
				VALUES (:userId, 'IDENTITY_VERIFICATION',
				    jsonb_build_object('terms', jsonb_build_object('service', :service, 'privacy', :privacy),
				                       'termsAcceptedAt', CURRENT_TIMESTAMP),
				    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 days')
				""")
				.param("userId", userId)
				.param("service", DRAFT_VERSION)
				.param("privacy", DRAFT_VERSION)
				.update();
		return Outcome.ACCEPTED;
	}

	public enum Outcome {
		ACCEPTED, INVALID_CONSENTS, CONFLICT
	}
}
