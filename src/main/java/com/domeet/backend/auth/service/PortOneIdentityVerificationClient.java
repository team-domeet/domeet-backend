package com.domeet.backend.auth.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public class PortOneIdentityVerificationClient {

	private final String storeId;
	private final String channelKey;
	private final String apiSecret;
	private final RestClient client;

	@Autowired
	public PortOneIdentityVerificationClient(
			@Value("${PORTONE_STORE_ID:}") String storeId,
			@Value("${PORTONE_IDENTITY_VERIFICATION_CHANNEL_KEY:}") String channelKey,
			@Value("${PORTONE_API_SECRET:}") String apiSecret) {
		this(storeId, channelKey, apiSecret, defaultClientBuilder());
	}

	PortOneIdentityVerificationClient(String storeId, String channelKey, String apiSecret, RestClient.Builder builder) {
		this.storeId = storeId.trim();
		this.channelKey = channelKey.trim();
		this.apiSecret = apiSecret.trim();
		this.client = builder.baseUrl("https://api.portone.io").build();
	}

	private static RestClient.Builder defaultClientBuilder() {
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
		factory.setReadTimeout(Duration.ofSeconds(6));
		return RestClient.builder().requestFactory(factory);
	}

	public Optional<TestChannel> testChannel() {
		if (!StringUtils.hasText(storeId) || !StringUtils.hasText(channelKey) || !StringUtils.hasText(apiSecret)) {
			return Optional.empty();
		}
		return Optional.of(new TestChannel(storeId, channelKey));
	}

	public Optional<VerifiedFields> findVerifiedFields(UUID id) {
		try {
			JsonNode result = client.get()
					.uri("/identity-verifications/{id}?storeId={storeId}", id, storeId)
					.header(HttpHeaders.AUTHORIZATION, "PortOne " + apiSecret)
					.retrieve()
					.body(JsonNode.class);
			if (result == null || !id.toString().equals(result.path("id").asString(null))) {
				throw new LookupUnavailable();
			}
			JsonNode channel = result.path("channel");
			if (!"TEST".equals(channel.path("type").asString(null))
					|| !channelKey.equals(channel.path("key").asString(null))
					|| !"VERIFIED".equals(result.path("status").asString(null))) {
				return Optional.empty();
			}
			JsonNode customer = result.path("verifiedCustomer");
			return Optional.of(new VerifiedFields(hasText(customer.path("birthDate")), hasText(customer.path("ci"))));
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				return Optional.empty();
			}
			throw new LookupUnavailable();
		} catch (RestClientException exception) {
			throw new LookupUnavailable();
		}
	}

	private boolean hasText(JsonNode value) {
		return value.isString() && StringUtils.hasText(value.asString());
	}

	public record TestChannel(String storeId, String channelKey) {
	}

	public record VerifiedFields(boolean birthDateAvailable, boolean ciAvailable) {
	}

	public static class LookupUnavailable extends RuntimeException {
	}
}
