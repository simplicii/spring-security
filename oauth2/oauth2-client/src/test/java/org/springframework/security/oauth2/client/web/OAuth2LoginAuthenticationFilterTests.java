/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.client.web;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link OAuth2LoginAuthenticationFilter}.
 *
 * @author Joe Grandja
 */
public class OAuth2LoginAuthenticationFilterTests {

	@Test
	public void doFilterWhenNotAuthorizationCodeResponseThenContinueChain() throws Exception {
		ClientRegistration clientRegistration = TestUtil.googleClientRegistration();

		OAuth2LoginAuthenticationFilter filter = Mockito.spy(setupFilter(clientRegistration));

		String requestURI = "/path";
		MockHttpServletRequest request = new MockHttpServletRequest("GET", requestURI);
		request.setServletPath(requestURI);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		filter.doFilter(request, response, filterChain);

		Mockito.verify(filterChain).doFilter(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class));
		Mockito.verify(filter, Mockito.never()).attemptAuthentication(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class));
	}

	@Test
	public void doFilterWhenAuthorizationCodeErrorResponseThenAuthenticationFailureHandlerIsCalled() throws Exception {
		ClientRegistration clientRegistration = TestUtil.githubClientRegistration();

		OAuth2LoginAuthenticationFilter filter = Mockito.spy(setupFilter(clientRegistration));
		AuthenticationFailureHandler failureHandler = mock(AuthenticationFailureHandler.class);
		filter.setAuthenticationFailureHandler(failureHandler);

		MockHttpServletRequest request = this.setupRequest(clientRegistration);
		String errorCode = OAuth2ErrorCodes.INVALID_GRANT;
		request.addParameter(OAuth2ParameterNames.ERROR, errorCode);
		request.addParameter(OAuth2ParameterNames.STATE, "some state");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		filter.doFilter(request, response, filterChain);

		Mockito.verify(filter).attemptAuthentication(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class));
		Mockito.verify(failureHandler).onAuthenticationFailure(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class),
				Matchers.any(AuthenticationException.class));
	}

	@Test
	public void doFilterWhenAuthorizationCodeSuccessResponseThenAuthenticationSuccessHandlerIsCalled() throws Exception {
		ClientRegistration clientRegistration = TestUtil.githubClientRegistration();
		OAuth2User oauth2User = mock(OAuth2User.class);
		when(oauth2User.getName()).thenReturn("principal name");
		OAuth2LoginAuthenticationToken loginAuthentication = mock(OAuth2LoginAuthenticationToken.class);
		when(loginAuthentication.getPrincipal()).thenReturn(oauth2User);
		when(loginAuthentication.getClientRegistration()).thenReturn(clientRegistration);
		when(loginAuthentication.getAccessToken()).thenReturn(mock(OAuth2AccessToken.class));

		OAuth2AuthenticationToken userAuthentication = new OAuth2AuthenticationToken(
			oauth2User, AuthorityUtils.NO_AUTHORITIES, clientRegistration.getRegistrationId());
		SecurityContextHolder.getContext().setAuthentication(userAuthentication);
		AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
		when(authenticationManager.authenticate(Matchers.any(Authentication.class))).thenReturn(loginAuthentication);

		OAuth2LoginAuthenticationFilter filter = Mockito.spy(setupFilter(authenticationManager, clientRegistration));
		AuthenticationSuccessHandler successHandler = mock(AuthenticationSuccessHandler.class);
		filter.setAuthenticationSuccessHandler(successHandler);
		AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository =
			new HttpSessionOAuth2AuthorizationRequestRepository();
		filter.setAuthorizationRequestRepository(authorizationRequestRepository);

		MockHttpServletRequest request = this.setupRequest(clientRegistration);
		String authCode = "some code";
		String state = "some state";
		request.addParameter(OAuth2ParameterNames.CODE, authCode);
		request.addParameter(OAuth2ParameterNames.STATE, state);
		MockHttpServletResponse response = new MockHttpServletResponse();
		setupAuthorizationRequest(authorizationRequestRepository, request, response, clientRegistration, state);
		FilterChain filterChain = mock(FilterChain.class);

		filter.doFilter(request, response, filterChain);

		Mockito.verify(filter).attemptAuthentication(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class));

		ArgumentCaptor<Authentication> authenticationArgCaptor = ArgumentCaptor.forClass(Authentication.class);
		Mockito.verify(successHandler).onAuthenticationSuccess(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class),
				authenticationArgCaptor.capture());
		Assertions.assertThat(authenticationArgCaptor.getValue()).isEqualTo(userAuthentication);
	}

	@Test
	public void doFilterWhenAuthorizationCodeSuccessResponseAndNoMatchingAuthorizationRequestThenThrowOAuth2AuthenticationExceptionAuthorizationRequestNotFound() throws Exception {
		ClientRegistration clientRegistration = TestUtil.githubClientRegistration();

		OAuth2LoginAuthenticationFilter filter = Mockito.spy(setupFilter(clientRegistration));
		AuthenticationFailureHandler failureHandler = mock(AuthenticationFailureHandler.class);
		filter.setAuthenticationFailureHandler(failureHandler);

		MockHttpServletRequest request = this.setupRequest(clientRegistration);
		String authCode = "some code";
		String state = "some state";
		request.addParameter(OAuth2ParameterNames.CODE, authCode);
		request.addParameter(OAuth2ParameterNames.STATE, state);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain filterChain = mock(FilterChain.class);

		filter.doFilter(request, response, filterChain);

		verifyThrowsOAuth2AuthenticationExceptionWithErrorCode(filter, failureHandler, "authorization_request_not_found");
	}

	private void verifyThrowsOAuth2AuthenticationExceptionWithErrorCode(OAuth2LoginAuthenticationFilter filter,
																		AuthenticationFailureHandler failureHandler,
																		String errorCode) throws Exception {

		Mockito.verify(filter).attemptAuthentication(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class));

		ArgumentCaptor<AuthenticationException> authenticationExceptionArgCaptor =
				ArgumentCaptor.forClass(AuthenticationException.class);
		Mockito.verify(failureHandler).onAuthenticationFailure(Matchers.any(HttpServletRequest.class), Matchers.any(HttpServletResponse.class),
				authenticationExceptionArgCaptor.capture());
		Assertions.assertThat(authenticationExceptionArgCaptor.getValue()).isInstanceOf(OAuth2AuthenticationException.class);
		OAuth2AuthenticationException oauth2AuthenticationException =
				(OAuth2AuthenticationException)authenticationExceptionArgCaptor.getValue();
		Assertions.assertThat(oauth2AuthenticationException.getError()).isNotNull();
		Assertions.assertThat(oauth2AuthenticationException.getError().getErrorCode()).isEqualTo(errorCode);
	}

	private OAuth2LoginAuthenticationFilter setupFilter(ClientRegistration... clientRegistrations) throws Exception {
		AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

		return setupFilter(authenticationManager, clientRegistrations);
	}

	private OAuth2LoginAuthenticationFilter setupFilter(
			AuthenticationManager authenticationManager, ClientRegistration... clientRegistrations) throws Exception {

		ClientRegistrationRepository clientRegistrationRepository = new InMemoryClientRegistrationRepository(clientRegistrations);

		OAuth2LoginAuthenticationFilter filter = new OAuth2LoginAuthenticationFilter(
			clientRegistrationRepository, mock(OAuth2AuthorizedClientService.class));
		filter.setAuthenticationManager(authenticationManager);

		return filter;
	}

	private void setupAuthorizationRequest(AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository,
											HttpServletRequest request,
											HttpServletResponse response,
											ClientRegistration clientRegistration,
											String state) {

		Map<String,Object> additionalParameters = new HashMap<>();
		additionalParameters.put(OAuth2ParameterNames.REGISTRATION_ID, clientRegistration.getRegistrationId());

		OAuth2AuthorizationRequest authorizationRequest =
			OAuth2AuthorizationRequest.authorizationCode()
				.clientId(clientRegistration.getClientId())
				.authorizationUri(clientRegistration.getProviderDetails().getAuthorizationUri())
				.redirectUri(clientRegistration.getRedirectUri())
				.scopes(clientRegistration.getScopes())
				.state(state)
				.additionalParameters(additionalParameters)
				.build();

		authorizationRequestRepository.saveAuthorizationRequest(authorizationRequest, request, response);
	}

	private MockHttpServletRequest setupRequest(ClientRegistration clientRegistration) {
		String requestURI = TestUtil.AUTHORIZE_BASE_URI + "/" + clientRegistration.getRegistrationId();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", requestURI);
		request.setScheme(TestUtil.DEFAULT_SCHEME);
		request.setServerName(TestUtil.DEFAULT_SERVER_NAME);
		request.setServerPort(TestUtil.DEFAULT_SERVER_PORT);
		request.setServletPath(requestURI);
		return request;
	}
}
