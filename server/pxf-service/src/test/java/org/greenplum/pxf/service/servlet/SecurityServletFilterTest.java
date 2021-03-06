package org.greenplum.pxf.service.servlet;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.greenplum.pxf.api.model.ConfigurationFactory;
import org.greenplum.pxf.api.security.SecureLogin;
import org.greenplum.pxf.service.SessionId;
import org.greenplum.pxf.service.UGICache;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.servlet.FilterChain;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import java.security.PrivilegedExceptionAction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SecurityServletFilterTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private SecurityServletFilter filter;

    @Mock
    private HttpServletRequest mockServletRequest;
    @Mock
    private ServletResponse mockServletResponse;
    @Mock
    private FilterChain mockFilterChain;
    @Mock
    private ConfigurationFactory mockConfigurationFactory;
    @Mock
    private SecureLogin mockSecureLogin;
    @Mock
    private UGICache mockUGICache;
    @Mock
    private Configuration mockConfiguration;
    @Mock
    private UserGroupInformation mockLoginUGI;
    @Mock
    private UserGroupInformation mockProxyUGI;

    @Captor
    private ArgumentCaptor<SessionId> session;

    @Before
    public void setup() {
        filter = new SecurityServletFilter(mockConfigurationFactory, mockSecureLogin, mockUGICache);
    }

    /* ----------- methods that test checking for required headers ----------- */

    @Test
    public void throwsWhenRequiredUserIdHeaderIsEmpty() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-USER is empty in the request");
        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("  ");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }

    @Test
    public void throwsWhenRequiredUserIdHeaderIsMissing() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-USER is missing in the request");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }

    @Test
    public void throwsWhenRequiredTxnIdHeaderIsEmpty() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-XID is empty in the request");
        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("user");
        when(mockServletRequest.getHeader("X-GP-XID")).thenReturn("  ");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }

    @Test
    public void throwsWhenRequiredTxnIdHeaderIsMissing() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-XID is missing in the request");
        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("user");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }
    @Test
    public void throwsWhenRequiredSegIdHeaderIsEmpty() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-SEGMENT-ID is empty in the request");
        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("user");
        when(mockServletRequest.getHeader("X-GP-XID")).thenReturn("xid");
        when(mockServletRequest.getHeader("X-GP-SEGMENT-ID")).thenReturn("  ");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }

    @Test
    public void throwsWhenRequiredSegIdHeaderIsMissing() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Header X-GP-SEGMENT-ID is missing in the request");
        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("user");
        when(mockServletRequest.getHeader("X-GP-XID")).thenReturn("xid");
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
    }

    /* ----------- methods that test determining remote user ----------- */

    @Test
    public void determineRemoteUser_IsLoginUser_NoKerberos_NoImpersonation_NoServiceUser() throws Exception {
        expectScenario(false, false, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("login-user", false);
    }

    @Test
    public void determineRemoteUser_IsServiceUser_NoKerberos_NoImpersonation_ServiceUser() throws Exception {
        expectScenario(false, false, true);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        // you would expect to find "service-user" here, and SecureLogin would set it as such
        // but our mocking logic is simple and always returns "login-user"
        // we are proving that we do not over-ride whatever SecureLogin returns in this case
        verifyScenario("login-user", false);
    }

    @Test
    public void determineRemoteUser_IsGpdbUser_NoKerberos_Impersonation_NoServiceUser() throws Exception {
        expectScenario(false, true, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("gpdb-user", true);
    }

    @Test
    public void determineRemoteUser_IsGpdbUser_NoKerberos_Impersonation_ServiceUser() throws Exception {
        expectScenario(false, true, true);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("gpdb-user", true);
    }

    @Test
    public void determineRemoteUser_IsLoginUser_Kerberos_NoImpersonation_NoServiceUser() throws Exception {
        expectScenario(true, false, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("login-user", false);
    }

    @Test
    public void determineRemoteUser_IsServiceUser_Kerberos_NoImpersonation_ServiceUser() throws Exception {
        expectScenario(true, false, true);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("service-user", false);
    }

    @Test
    public void determineRemoteUser_IsGpdbUser_Kerberos_Impersonation_NoServiceUser() throws Exception {
        expectScenario(true, true, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("gpdb-user", true);
    }

    @Test
    public void determineRemoteUser_IsGpdbUser_Kerberos_Impersonation_ServiceUser() throws Exception {
        expectScenario(true, true, true);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("gpdb-user", true);
    }

    /* ----------- methods that test cleaning UGI cache ----------- */

    @Test
    public void doesNotCleanTheUGICacheOnNonLastCalls() throws Exception {
        expectScenario(false, false, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("login-user", false);
        verify(mockUGICache).release(any(SessionId.class), eq(false));
    }

    @Test
    public void tellsTheUGICacheToCleanItselfOnTheLastCallForASegment() throws Exception {
        when(mockServletRequest.getHeader("X-GP-LAST-FRAGMENT")).thenReturn("true");
        expectScenario(false, false, false);
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain);
        verifyScenario("login-user", false);
        verify(mockUGICache).release(any(SessionId.class), eq(true));
    }

    /* ----------- helper methods ----------- */

    private void expectScenario(boolean kerberos, boolean impersonation, boolean serviceUser) throws Exception {
        when(mockConfiguration.get("hadoop.security.authentication", "simple"))
                .thenReturn(kerberos ? "kerberos" : "simple");
        when(mockSecureLogin.isUserImpersonationEnabled(mockConfiguration)).thenReturn(impersonation);
        when(mockLoginUGI.getUserName()).thenReturn("login-user");

        if (serviceUser) {
            when(mockConfiguration.get("pxf.service.user.name")).thenReturn("service-user");
        }

        when(mockServletRequest.getHeader("X-GP-USER")).thenReturn("gpdb-user");
        when(mockServletRequest.getHeader("X-GP-XID")).thenReturn("xid");
        when(mockServletRequest.getHeader("X-GP-SEGMENT-ID")).thenReturn("7");
        when(mockServletRequest.getHeader("X-GP-OPTIONS-SERVER")).thenReturn("server");
        when(mockServletRequest.getHeader("X-GP-OPTIONS-CONFIG")).thenReturn("config");
        when(mockConfigurationFactory.initConfiguration("config", "server", "gpdb-user", null)).thenReturn(mockConfiguration);
        when(mockSecureLogin.getLoginUser("server", "config", mockConfiguration)).thenReturn(mockLoginUGI);
        when(mockUGICache.getUserGroupInformation(any(SessionId.class), eq(impersonation))).thenReturn(mockProxyUGI);
    }

    private void verifyScenario(String user, boolean impersonation) throws Exception {
        verify(mockUGICache).getUserGroupInformation(session.capture(), eq(impersonation));
        verify(mockProxyUGI).doAs(Matchers.<PrivilegedExceptionAction<Object>>any());
        assertEquals(user, session.getValue().getUser());
        assertEquals(7, session.getValue().getSegmentId().intValue());
        assertSame(mockConfiguration, session.getValue().getConfiguration());
        assertSame(mockLoginUGI, session.getValue().getLoginUser());
    }
}
