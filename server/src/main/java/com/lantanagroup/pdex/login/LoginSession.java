package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.proxy.ProxyDestinations;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.util.WebUtils;

/**
 * The sign-in state in the servlet session: the saved client values for each server, one pending sign-in, one
 * token set for each server, and one error for each server after a failed sign-in.
 */
public final class LoginSession {

	private static final String ATTRIBUTE = LoginSession.class.getName();

	public enum ClientAuth { BASIC, NONE, UDAP }

	/**
	 * The client values that the settings dialog saved for one server. fhirBase has no trailing slash
	 * (Target.fhirBaseWithoutSlash). An empty secret is a public client. With udap true, the sign-in uses the
	 * certificate of this app, and the client id and the secret are kept for a later save of method client. The
	 * values stay until a save for the same server or the end of the session.
	 */
	public record Client(LoginClients.Kind kind, URI fhirBase, String clientId, String secret, String scope,
			boolean udap) {

		public ClientAuth clientAuth() {
			if (udap) {
				return ClientAuth.UDAP;
			}
			return secret == null || secret.isEmpty() ? ClientAuth.NONE : ClientAuth.BASIC;
		}

		// A log line or an error page must never hold the client secret.
		@Override
		public String toString() {
			return "Client[kind=" + kind + "]";
		}
	}

	public record Pending(String server, LoginClients.Target target, String clientId, ClientAuth clientAuth,
			String secret, String scope, URI redirectUri, State state, CodeVerifier verifier, LoginClients.Discovery discovery) {

		// A log line or an error page must never hold the client secret or the code verifier.
		@Override
		public String toString() {
			return "Pending[kind=" + (target == null ? null : target.kind()) + "]";
		}
	}

	/**
	 * fhirBase has no trailing slash (Target.fhirBaseWithoutSlash). expiresAt is null when the token response has no
	 * expires_in. Such a set stays until sign-out or the end of the session.
	 */
	public record TokenSet(LoginClients.Kind kind, URI fhirBase, String accessToken, Instant expiresAt, String scope,
			String patient, String fhirUser, boolean patientMissing) {

		// A log line or an error page must never hold the access token.
		@Override
		public String toString() {
			return "TokenSet[kind=" + kind + "]";
		}

		private boolean expired() {
			return expiresAt != null && !Instant.now().isBefore(expiresAt);
		}
	}

	private Pending pending;
	private final List<Client> clients = new ArrayList<>();
	private final List<TokenSet> tokenSets = new ArrayList<>();
	private final Map<String, String> errors = new HashMap<>();

	private LoginSession() {}

	/** Two parallel requests of one session get the same holder. */
	public static LoginSession of(HttpSession session) {
		synchronized (WebUtils.getSessionMutex(session)) {
			LoginSession login = (LoginSession) session.getAttribute(ATTRIBUTE);
			if (login == null) {
				login = new LoginSession();
				session.setAttribute(ATTRIBUTE, login);
			}
			return login;
		}
	}

	public static Optional<LoginSession> find(HttpSession session) {
		return session == null ? Optional.empty() : Optional.ofNullable((LoginSession) session.getAttribute(ATTRIBUTE));
	}

	/** A save for a server that has saved values replaces them. */
	public synchronized void putClient(Client client) {
		clients.removeIf(existing -> matches(existing.kind(), existing.fhirBase(), client.kind(), client.fhirBase()));
		clients.add(client);
	}

	public synchronized Optional<Client> client(LoginClients.Target target) {
		return clients.stream()
				.filter(client -> matches(client.kind(), client.fhirBase(), target.kind(), target.fhirBaseWithoutSlash()))
				.findFirst();
	}

	public synchronized void start(Pending next) {
		pending = next;
	}

	/**
	 * The pending sign-in leaves the session in the same step as the state check, so one callback can use it only
	 * one time. A page of a different site can open the callback with a wrong state, and that callback leaves the
	 * sign-in in place.
	 *
	 * @return the pending sign-in when its state is equal to the given state, else null
	 */
	public synchronized Pending takePending(String state) {
		if (pending == null || !pending.state().getValue().equals(state)) {
			return null;
		}
		Pending taken = pending;
		pending = null;
		return taken;
	}

	public synchronized void put(TokenSet set) {
		tokenSets.removeIf(existing -> matches(existing.kind(), existing.fhirBase(), set.kind(), set.fhirBase()));
		tokenSets.add(set);
	}

	public synchronized void remove(LoginClients.Kind kind, URI fhirBase) {
		tokenSets.removeIf(existing -> matches(existing.kind(), existing.fhirBase(), kind, fhirBase));
	}

	public synchronized Optional<TokenSet> forTarget(LoginClients.Target target) {
		removeExpired();
		return tokenSets.stream()
				.filter(set -> matches(set.kind(), set.fhirBase(), target.kind(), target.fhirBaseWithoutSlash()))
				.findFirst();
	}

	public synchronized Optional<TokenSet> own() {
		removeExpired();
		return tokenSets.stream().filter(set -> set.kind() == LoginClients.Kind.OWN).findFirst();
	}

	/**
	 * The match uses the parsed URI and the isUnder rule of the proxy for a listed server. When more than one token
	 * set matches, the set with the longest base path is the most specific server and gives the token.
	 */
	public synchronized Optional<TokenSet> forDestination(URI destination) {
		removeExpired();
		return tokenSets.stream()
				.filter(set -> set.kind() != LoginClients.Kind.OWN && ProxyDestinations.isUnder(destination, set.fhirBase()))
				.max(Comparator.comparingInt(set -> set.fhirBase().getRawPath().length()));
	}

	public synchronized void error(String server, String reason) {
		errors.put(server, reason);
	}

	public synchronized String takeError(String server) {
		return errors.remove(server);
	}

	private void removeExpired() {
		tokenSets.removeIf(TokenSet::expired);
	}

	// The status call and the login call of the own server can see different origins, for example behind
	// Vite or a TLS proxy. So the own server has at most one token set and one set of client values, matched by
	// kind only.
	private static boolean matches(LoginClients.Kind storedKind, URI storedBase, LoginClients.Kind kind, URI fhirBase) {
		return storedKind == kind && (kind == LoginClients.Kind.OWN || storedBase.equals(fhirBase));
	}
}
