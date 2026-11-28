package com.domeet.backend.auth.service;

import com.domeet.backend.auth.service.PortOneIdentityVerificationClient.VerifiedFields;
import com.domeet.backend.auth.service.SignupTokenIssuer.VerifiedGoogleToken;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SignupIdentityVerificationService {

	private static final String ELIGIBLE_USER = """
			SELECT p.user_id
			FROM signup_progress p
			JOIN users u ON u.id = p.user_id
			JOIN social_accounts a ON a.user_id = u.id
			WHERE a.provider = 'GOOGLE' AND a.provider_user_id = :subject
			  AND u.status = 'PENDING' AND p.step = 'IDENTITY_VERIFICATION'
			  AND p.expires_at > statement_timestamp()
			""";

	private final JdbcClient jdbc;
	private final PortOneIdentityVerificationClient portOne;

	public SignupIdentityVerificationService(JdbcClient jdbc, PortOneIdentityVerificationClient portOne) {
		this.jdbc = jdbc;
		this.portOne = portOne;
	}

	public StartedAttempt start(VerifiedGoogleToken token) {
		var channel = portOne.testChannel().orElseThrow(PortOneIdentityVerificationClient.LookupUnavailable::new);
		UUID id = UUID.randomUUID();
		return jdbc.sql("""
				INSERT INTO signup_identity_verification_attempts (id, user_id, created_at, expires_at)
				SELECT :id, eligible.user_id, statement_timestamp(),
				    LEAST(:tokenExpiry, statement_timestamp() + INTERVAL '15 minutes')
				FROM (%s) eligible
				WHERE :tokenExpiry > statement_timestamp()
				RETURNING expires_at
				""".formatted(ELIGIBLE_USER))
				.param("id", id)
				.param("subject", token.subject())
				.param("tokenExpiry", token.expiresAt().atOffset(ZoneOffset.UTC))
				.query((row, number) -> new StartedAttempt(id, channel.storeId(), channel.channelKey(),
						row.getObject("expires_at", OffsetDateTime.class).toInstant()))
				.optional()
				.orElseThrow(SignupStateConflict::new);
	}

	public Confirmation confirm(VerifiedGoogleToken token, UUID id) {
		Attempt attempt = getActiveAttempt(token, id);
		if (attempt.confirmed()) {
			return new Confirmation("VERIFIED", attempt.birthDateAvailable(), attempt.ciAvailable());
		}

		portOne.testChannel().orElseThrow(PortOneIdentityVerificationClient.LookupUnavailable::new);
		VerifiedFields fields = portOne.findVerifiedFields(id).orElseThrow(VerificationNotCompleted::new);
		int updated = jdbc.sql("""
				UPDATE signup_identity_verification_attempts
				SET confirmed_at = statement_timestamp(), birth_date_available = :birthDate, ci_available = :ci
				WHERE id = :id AND user_id IN (%s)
				  AND confirmed_at IS NULL AND expires_at > statement_timestamp()
				""".formatted(ELIGIBLE_USER))
				.param("id", id)
				.param("subject", token.subject())
				.param("birthDate", fields.birthDateAvailable())
				.param("ci", fields.ciAvailable())
				.update();
		if (updated == 1) {
			return new Confirmation("VERIFIED", fields.birthDateAvailable(), fields.ciAvailable());
		}
		// A concurrent confirmation may have saved the result while PortOne was queried.
		Attempt current = getActiveAttempt(token, id);
		if (!current.confirmed()) {
			throw new SignupStateConflict();
		}
		return new Confirmation("VERIFIED", current.birthDateAvailable(), current.ciAvailable());
	}

	private Attempt getActiveAttempt(VerifiedGoogleToken token, UUID id) {
		Optional<Attempt> attempt = jdbc.sql("""
				SELECT expires_at <= statement_timestamp() AS expired, confirmed_at IS NOT NULL AS confirmed,
				    birth_date_available, ci_available
				FROM signup_identity_verification_attempts
				WHERE id = :id AND user_id IN (%s)
				""".formatted(ELIGIBLE_USER))
				.param("id", id)
				.param("subject", token.subject())
				.query((row, number) -> new Attempt(row.getBoolean("expired"), row.getBoolean("confirmed"),
						row.getBoolean("birth_date_available"), row.getBoolean("ci_available")))
				.optional();
		Attempt result = attempt.orElseThrow(AttemptNotFound::new);
		if (result.expired()) {
			throw new AttemptExpired();
		}
		return result;
	}

	private record Attempt(boolean expired, boolean confirmed, boolean birthDateAvailable, boolean ciAvailable) {
	}

	public record StartedAttempt(UUID identityVerificationId, String storeId, String channelKey, Instant expiresAt) {
	}

	public record Confirmation(String status, boolean birthDateAvailable, boolean ciAvailable) {
	}

	public static class SignupStateConflict extends RuntimeException {
	}

	public static class AttemptNotFound extends RuntimeException {
	}

	public static class AttemptExpired extends RuntimeException {
	}

	public static class VerificationNotCompleted extends RuntimeException {
	}
}
