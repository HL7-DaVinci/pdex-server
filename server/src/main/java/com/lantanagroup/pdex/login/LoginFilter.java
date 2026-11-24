package com.lantanagroup.pdex.login;

import com.lantanagroup.pdex.login.LoginSession.TokenSet;
import com.lantanagroup.pdex.proxy.ProxyDestinations;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the access token of the session to a request for /fhir/* or /proxy when the request shows that the app
 * sent it. A request with its own Authorization header keeps that header.
 */
public class LoginFilter extends OncePerRequestFilter {

	static final String APP_HEADER = "X-App-Request";

	/**
	 * A page of a different origin cannot send a header of its own without a CORS preflight, and the CORS lists
	 * of the server do not permit this header. A browser sends Sec-Fetch-Site only to https addresses, to
	 * localhost, and to loopback IP addresses, so a request without it passes on the app header alone.
	 */
	public static boolean hasProof(HttpServletRequest request) {
		String site = request.getHeader("Sec-Fetch-Site");
		return request.getHeader(APP_HEADER) != null && (site == null || "same-origin".equals(site));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<LoginSession> login = LoginSession.find(request.getSession(false));
		if (login.isEmpty() || !hasProof(request) || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
			chain.doFilter(request, response);
			return;
		}
		Optional<TokenSet> tokens = "/proxy".equals(request.getServletPath())
				? forProxyDestination(login.get(), request)
				: login.get().own();
		chain.doFilter(tokens.<HttpServletRequest>map(set -> new BearerRequest(request, "Bearer " + set.accessToken()))
				.orElse(request), response);
	}

	// The proxy controller reads the url value from the raw query string. getParameter would read a form body,
	// and the controller would then refuse the request. A url value that is not valid gets no token, and the
	// controller refuses it.
	private static Optional<TokenSet> forProxyDestination(LoginSession login, HttpServletRequest request) {
		try {
			return login.forDestination(ProxyDestinations.parse(ProxyDestinations.urlValue(request.getQueryString())));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	// HAPI reads a header through getHeader or getHeaders, and the proxy controller copies the headers through
	// getHeaderNames, so all three give the added header.
	private static final class BearerRequest extends HttpServletRequestWrapper {

		private final String authorization;

		BearerRequest(HttpServletRequest request, String authorization) {
			super(request);
			this.authorization = authorization;
		}

		@Override
		public String getHeader(String name) {
			return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
		}

		@Override
		public Enumeration<String> getHeaders(String name) {
			return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
					? Collections.enumeration(List.of(authorization))
					: super.getHeaders(name);
		}

		@Override
		public Enumeration<String> getHeaderNames() {
			List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
			names.add(HttpHeaders.AUTHORIZATION);
			return Collections.enumeration(names);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "app.login.enabled", havingValue = "true")
	static class Registration {

		@Bean
		FilterRegistrationBean<LoginFilter> loginFilter() {
			FilterRegistrationBean<LoginFilter> registration = new FilterRegistrationBean<>(new LoginFilter());
			registration.addUrlPatterns("/fhir/*", "/proxy");
			return registration;
		}
	}
}
