/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cxf.fediz.service.idp.kerberos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.util.Assert;
import org.springframework.web.filter.GenericFilterBean;
/**
 * Parses the SPNEGO authentication Header, which was generated by the browser
 * and creates a {@link KerberosServiceRequestToken} out if it. It will then
 * call the {@link AuthenticationManager}.
 *
 * @author Mike Wiesner
 * @since 1.0
 * @version $Id$
 * @see KerberosServiceAuthenticationProvider
 * @see KerberosEntryPoint
 */
public class KerberosAuthenticationProcessingFilter extends GenericFilterBean {
    private AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource =
        new WebAuthenticationDetailsSource();
    private AuthenticationManager authenticationManager;
    private AuthenticationSuccessHandler successHandler;
    private AuthenticationFailureHandler failureHandler;
    private SessionAuthenticationStrategy sessionStrategy = new NullAuthenticatedSessionStrategy();
    private boolean skipIfAlreadyAuthenticated = true;
    /*
     * (non-Javadoc)
     *
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
     * javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        if (skipIfAlreadyAuthenticated) {
            Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
            if (existingAuth != null && existingAuth.isAuthenticated()
                && !(existingAuth instanceof AnonymousAuthenticationToken)) {
                chain.doFilter(request, response);
                return;
            }
        }
        String header = request.getHeader("Authorization");
        if ((header != null) && header.startsWith("Negotiate ")) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received Negotiate Header for request " + request.getRequestURL() + ": " + header);
            }
            byte[] base64Token = header.substring(10).getBytes(StandardCharsets.UTF_8);
            byte[] kerberosTicket = Base64.decode(base64Token);
            KerberosServiceRequestToken authenticationRequest = new KerberosServiceRequestToken(kerberosTicket);
            authenticationRequest.setDetails(authenticationDetailsSource.buildDetails(request));
            Authentication authentication;
            try {
                authentication = authenticationManager.authenticate(authenticationRequest);
            } catch (AuthenticationException e) {
                //That shouldn't happen, as it is most likely a wrong
                //configuration on the server side
                logger.warn("Negotiate Header was invalid: " + header, e);
                SecurityContextHolder.clearContext();
                if (failureHandler != null) {
                    failureHandler.onAuthenticationFailure(request, response, e);
                } else {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.flushBuffer();
                }
                return;
            }
            sessionStrategy.onAuthentication(authentication, request, response);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (successHandler != null) {
                successHandler.onAuthenticationSuccess(request, response, authentication);
            }
        }
        chain.doFilter(request, response);
    }
    /**
     * The authentication manager for validating the ticket.
     *
     * @param authenticationManager
     */
    public void setAuthenticationManager(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }
    /**
     * This handler is called after a successful authentication. One can add
     * additional authentication behavior by setting this.<br />
     * Default is null, which means nothing additional happens
     *
     * @param successHandler
     */
    public void setSuccessHandler(AuthenticationSuccessHandler successHandler) {
        this.successHandler = successHandler;
    }
    /**
     * This handler is called after a failure authentication. In most cases you
     * only get Kerberos/SPNEGO failures with a wrong server or network
     * configurations and not during runtime. If the client encounters an error,
     * it will just stop the communication with server and therefore this
     * handler will not be called in this case.<br />
     * Default is null, which means that the Filter returns the HTTP 500 code
     *
     * @param failureHandler
     */
    public void setFailureHandler(AuthenticationFailureHandler failureHandler) {
        this.failureHandler = failureHandler;
    }
    /**
     * Should Kerberos authentication be skipped if a user is already authenticated
     * for this request (e.g. in the HTTP session).
     *
     * @param skipIfAlreadyAuthenticated default is true
     */
    public void setSkipIfAlreadyAuthenticated(boolean skipIfAlreadyAuthenticated) {
        this.skipIfAlreadyAuthenticated = skipIfAlreadyAuthenticated;
    }
    /**
     * The session handling strategy which will be invoked immediately after an authentication request is
     * successfully processed by the <tt>AuthenticationManager</tt>. Used, for example, to handle changing of the
     * session identifier to prevent session fixation attacks.
     *
     * @param sessionAuthStrategy the implementation to use. If not set a null implementation is
     * used.
     */
    public void setSessionAuthenticationStrategy(SessionAuthenticationStrategy sessionAuthStrategy) {
        this.sessionStrategy = sessionAuthStrategy;
    }
    public void setAuthenticationDetailsSource(
        AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource) {
        Assert.notNull(authenticationDetailsSource, "AuthenticationDetailsSource required");
        this.authenticationDetailsSource = authenticationDetailsSource;
    }
    /*
     * (non-Javadoc)
     *
     * @see
     * org.springframework.web.filter.GenericFilterBean#afterPropertiesSet()
     */
    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        Assert.notNull(this.authenticationManager, "authenticationManager must be specified");
    }
}



