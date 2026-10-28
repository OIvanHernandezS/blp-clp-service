package com.liverpool.ms_clp_blp.clients.akamai;

import java.net.URI;

import com.akamai.edgegrid.signer.ClientCredential;
import com.akamai.edgegrid.signer.EdgeGridV1Signer;
import com.akamai.edgegrid.signer.exceptions.RequestSigningException;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Stamps the EdgeGrid {@code Authorization} header on every Akamai call.
 *
 * <p>Akamai authenticates with {@code EG1-HMAC-SHA256}: the header carries the client and access
 * tokens, a UTC timestamp, a per-request nonce and an HMAC over the method, scheme, host, path and a
 * SHA-256 hash of the body. The signature therefore covers the <em>resolved</em> request, which is why
 * this runs as a Feign interceptor rather than being precomputed — {@code /ccu/v3/{action}/…} still
 * holds unsubstituted path variables until Feign builds the template.</p>
 *
 * <p><strong>Why the official signer.</strong> The algorithm is unforgiving — a wrong tab, a stale
 * clock (±30s) or a mis-hashed body all surface as an opaque 403 that looks exactly like a revoked
 * credential. {@code com.akamai.edgegrid:edgegrid-signer-core} is Akamai's own implementation; it
 * publishes no Feign or OkHttp binding, so only the signature is borrowed and applied here.</p>
 *
 * <p>No credential is ever logged, put in an exception message, or copied into a field of anything
 * that is. The one header this writes is {@code Authorization}, which {@code SensitiveHeaderMasker}
 * already masks to {@code ***} in the outbound curl line — pinned by
 * {@code AkamaiEdgeGridInterceptorTest}, so the masking cannot silently regress.</p>
 */
public class AkamaiEdgeGridInterceptor implements RequestInterceptor {

	private static final String AUTHORIZATION = "Authorization";

	private final AkamaiProperties properties;
	private final EdgeGridV1Signer signer;

	/**
	 * @param properties EdgeGrid credentials and the API host
	 * @param signer     Akamai's signer; injected rather than constructed so a test can pin the
	 *                   timestamp and nonce and assert a byte-exact signature
	 */
	public AkamaiEdgeGridInterceptor(AkamaiProperties properties, EdgeGridV1Signer signer) {
		this.properties = properties;
		this.signer = signer;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IllegalStateException when the credential is incomplete, or when signing fails. Both
	 *         messages name the missing or offending <em>variable</em>, never a value — this is the
	 *         last line of defence behind {@code CachePurgeUseCases}, which already refuses an
	 *         unconfigured purge before it reaches the transport.
	 */
	@Override
	public void apply(RequestTemplate template) {
		if (!properties.configured()) {
			throw new IllegalStateException(
					"Akamai EdgeGrid credentials are not configured: set AKAMAI_HOST, AKAMAI_CLIENT_TOKEN, "
							+ "AKAMAI_CLIENT_SECRET and AKAMAI_ACCESS_TOKEN.");
		}
		try {
			template.header(AUTHORIZATION, signer.getSignature(signable(template), credential()));
		} catch (RequestSigningException e) {
			// The cause is deliberately not chained: RequestSigningException's message can echo request
			// state, and this exception travels out to an error handler that logs it.
			throw new IllegalStateException(
					"Could not sign the Akamai request with the configured EdgeGrid credential.");
		}
	}

	/**
	 * Builds the signer's view of the request.
	 *
	 * <p>The URL must be absolute and must match what actually goes on the wire: Feign applies the
	 * target <em>after</em> interceptors run, so at this point {@link RequestTemplate#url()} is only
	 * the path and the base URL has to be prepended by hand.</p>
	 *
	 * @param template the request Feign is about to send
	 * @return the same request in the signer's own shape
	 */
	private com.akamai.edgegrid.signer.Request signable(RequestTemplate template) {
		return com.akamai.edgegrid.signer.Request.builder()
				.method(template.method())
				.uri(URI.create(properties.baseUrl() + template.path() + template.queryLine()))
				.body(template.body() == null ? new byte[0] : template.body())
				.build();
	}

	private ClientCredential credential() {
		return ClientCredential.builder()
				.host(properties.host())
				.clientToken(properties.clientToken())
				.clientSecret(properties.clientSecret())
				.accessToken(properties.accessToken())
				.build();
	}
}
