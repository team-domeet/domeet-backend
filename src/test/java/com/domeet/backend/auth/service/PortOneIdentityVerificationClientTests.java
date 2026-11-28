package com.domeet.backend.auth.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PortOneIdentityVerificationClientTests {

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final PortOneIdentityVerificationClient client =
			new PortOneIdentityVerificationClient("test-store", "test-channel", "fixture-secret", builder);
	private final UUID id = UUID.randomUUID();

	@Test
	void extractsAvailabilityWithoutReturningRawCustomerData() {
		expectLookup().andRespond(withSuccess("""
				{"id":"%s","status":"VERIFIED","channel":{"type":"TEST","key":"test-channel"},
				 "verifiedCustomer":{"birthDate":"2000-01-01","ci":" "}}
				""".formatted(id), MediaType.APPLICATION_JSON));
		assertEquals(new PortOneIdentityVerificationClient.VerifiedFields(true, false),
				client.findVerifiedFields(id).orElseThrow());
		server.verify();
	}

	@Test
	void rejectsLiveChannelEvenForVerifiedResult() {
		expectLookup().andRespond(withSuccess("""
				{"id":"%s","status":"VERIFIED","channel":{"type":"LIVE","key":"test-channel"}}
				""".formatted(id), MediaType.APPLICATION_JSON));
		assertTrue(client.findVerifiedFields(id).isEmpty());
		server.verify();
	}

	@Test
	void rejectsDifferentTestChannel() {
		expectLookup().andRespond(withSuccess("""
				{"id":"%s","status":"VERIFIED","channel":{"type":"TEST","key":"another-channel"}}
				""".formatted(id), MediaType.APPLICATION_JSON));
		assertTrue(client.findVerifiedFields(id).isEmpty());
		server.verify();
	}

	@Test
	void treatsMissingResultAsIncomplete() {
		expectLookup().andRespond(withResourceNotFound());
		assertTrue(client.findVerifiedFields(id).isEmpty());
		server.verify();
	}

	@Test
	void sanitizesUpstreamFailure() {
		expectLookup().andRespond(withServerError().body("private provider details"));
		var error = assertThrows(PortOneIdentityVerificationClient.LookupUnavailable.class,
				() -> client.findVerifiedFields(id));
		assertEquals(null, error.getCause());
		server.verify();
	}

	private org.springframework.test.web.client.ResponseActions expectLookup() {
		return server.expect(requestTo("https://api.portone.io/identity-verifications/" + id + "?storeId=test-store"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "PortOne fixture-secret"));
	}
}
