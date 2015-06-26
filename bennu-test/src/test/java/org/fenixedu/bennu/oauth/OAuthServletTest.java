/**
 * Copyright © 2015 Instituto Superior Técnico
 *
 * This file is part of Bennu OAuth Test.
 *
 * Bennu OAuth Test is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bennu OAuth Test is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Bennu OAuth Test.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.fenixedu.bennu.oauth;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.fenixedu.bennu.core.domain.User;
import org.fenixedu.bennu.core.domain.UserProfile;
import org.fenixedu.bennu.core.security.Authenticate;
import org.fenixedu.bennu.oauth.domain.ApplicationUserAuthorization;
import org.fenixedu.bennu.oauth.domain.ApplicationUserSession;
import org.fenixedu.bennu.oauth.domain.ExternalApplication;
import org.fenixedu.bennu.oauth.domain.ExternalApplicationScope;
import org.fenixedu.bennu.oauth.domain.ServiceApplication;
import org.fenixedu.bennu.oauth.jaxrs.BennuOAuthFeature;
import org.fenixedu.bennu.oauth.servlets.OAuthAuthorizationServlet;
import org.fenixedu.commons.i18n.LocalizedString;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import pt.ist.fenixframework.Atomic;
import pt.ist.fenixframework.Atomic.TxMode;
import pt.ist.fenixframework.DomainObject;
import pt.ist.fenixframework.test.core.FenixFrameworkRunner;

import com.google.common.base.Joiner;
import com.google.common.io.BaseEncoding;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(FenixFrameworkRunner.class)
public class OAuthServletTest extends JerseyTest {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
    private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final String GRANT_TYPE = "grant_type";

    private final static String REDIRECT_URI = "redirect_uri";
    private final static String CODE = "code";
    private final static String REFRESH_TOKEN = "refresh_token";

    private static ExternalApplication externalApplication;
    private static ServiceApplication serviceApplication;
    private static OAuthAuthorizationServlet oauthServlet;
    private static User user1;
    private static ServiceApplication serviceApplicationWithScope;

    @Override
    protected Application configure() {
        return new ResourceConfig(TestResource.class, BennuOAuthFeature.class);
    }

    private static User createUser(String username, String firstName, String lastName, String fullName, String email) {
        return new User(username, new UserProfile(firstName, lastName, fullName, email, Locale.getDefault()));
    }

    @Atomic(mode = TxMode.WRITE)
    public static void initObjects() {
        final Locale ptPT = Locale.forLanguageTag("pt-PT");
        final Locale enGB = Locale.forLanguageTag("en-GB");

        if (user1 == null) {
            user1 = createUser("user1", "John", "Doe", "John Doe", "john.doe@fenixedu.org");
        }

        if (oauthServlet == null) {
            oauthServlet = new OAuthAuthorizationServlet();
        }

        if (serviceApplication == null) {
            serviceApplication = new ServiceApplication();
            serviceApplication.setAuthor(user1);
            serviceApplication.setName("Test Service Application");
            serviceApplication.setDescription("This is a test service application");
        }

        if (serviceApplicationWithScope == null) {
            serviceApplicationWithScope = new ServiceApplication();
            serviceApplicationWithScope.setAuthorName("John Doe");
            serviceApplicationWithScope.setName("Service App with scope");
            serviceApplicationWithScope.setDescription("Service App with scope SERVICE");
            ExternalApplicationScope scope = new ExternalApplicationScope();
            scope.setScopeKey("SERVICE");

            scope.setName(new LocalizedString.Builder().with(enGB, "Service Scope").build());
            scope.setDescription(new LocalizedString.Builder().with(enGB, "Service scope is for service only").build());
            scope.setService(Boolean.TRUE);
            serviceApplicationWithScope.addScopes(scope);
        }

        if (externalApplication == null) {
            externalApplication = new ExternalApplication();
            externalApplication.setAuthor(user1);
            externalApplication.setName("Test External Application");
            externalApplication.setDescription("This is a test external application");
            externalApplication.setRedirectUrl("http://test.url/callback");
        }
    }

    @BeforeClass
    public static void setup() {
        initObjects();
    }

    private String generateToken(DomainObject domainObject) {
        String random = "fenix";
        String token = Joiner.on(":").join(domainObject.getExternalId(), random);
        return Base64.getEncoder().encodeToString(token.getBytes()).replace("=", "").replace("+", "-").replace("/", "-");
    }

    @Test
    public void getAccessTokenHeaderTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();

        ExternalApplication externalApp = new ExternalApplication();
        externalApp.setAuthor(user1);
        externalApp.setName("Test External Application");
        externalApp.setDescription("This is a test external application");
        externalApp.setRedirectUrl("http://test.url/callback");

        ApplicationUserSession applicationUserSession = new ApplicationUserSession();
        applicationUserSession.setCode("fenixedu");

        ApplicationUserAuthorization applicationUserAuthorization = new ApplicationUserAuthorization(user1, externalApp);
        applicationUserAuthorization.addSession(applicationUserSession);
        externalApp.addApplicationUserAuthorization(applicationUserAuthorization);

        String clientSecret = externalApp.getExternalId() + ":" + externalApp.getSecret();
        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(clientSecret.getBytes()));
        req.addParameter(REDIRECT_URI, externalApp.getRedirectUrl());
        req.addParameter(CODE, applicationUserSession.getCode());
        req.addParameter(GRANT_TYPE, GRANT_TYPE_AUTHORIZATION_CODE);
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);
            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();
            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && token.get("access_token").getAsString().length() > 0);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getAccessTokenWrongClientIdHeaderTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();

        ExternalApplication externalApp = new ExternalApplication();
        externalApp.setAuthor(user1);
        externalApp.setName("Test External Application");
        externalApp.setDescription("This is a test external application");
        externalApp.setRedirectUrl("http://test.url/callback");

        ApplicationUserSession applicationUserSession = new ApplicationUserSession();
        applicationUserSession.setCode("fenixedu");

        ApplicationUserAuthorization applicationUserAuthorization = new ApplicationUserAuthorization(user1, externalApp);
        applicationUserAuthorization.addSession(applicationUserSession);
        externalApp.addApplicationUserAuthorization(applicationUserAuthorization);

        String clientSecret = "fenixedu:fenixedu";
        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(clientSecret.getBytes()));
        req.addParameter(REDIRECT_URI, externalApp.getRedirectUrl());
        req.addParameter(CODE, applicationUserSession.getCode());
        req.addParameter(GRANT_TYPE, GRANT_TYPE_AUTHORIZATION_CODE);
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);
            Assert.assertEquals("must return status BAD_REQUEST", 400, res.getStatus());
        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void refreshAccessTokenHeaderTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();

        ExternalApplication externalApp = new ExternalApplication();
        externalApp.setAuthor(user1);
        externalApp.setName("Test External Application");
        externalApp.setDescription("This is a test external application");
        externalApp.setRedirectUrl("http://test.url/callback");

        ApplicationUserSession applicationUserSession = new ApplicationUserSession();
        applicationUserSession.setTokens(generateToken(applicationUserSession), generateToken(applicationUserSession));

        ApplicationUserAuthorization applicationUserAuthorization = new ApplicationUserAuthorization(user1, externalApp);
        applicationUserAuthorization.addSession(applicationUserSession);

        externalApp.addApplicationUserAuthorization(applicationUserAuthorization);

        String clientSecret = externalApp.getExternalId() + ":" + externalApp.getSecret();
        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(clientSecret.getBytes()));
        req.addParameter(REFRESH_TOKEN, applicationUserSession.getRefreshToken());
        req.addParameter(GRANT_TYPE, GRANT_TYPE_REFRESH_TOKEN);
        req.setMethod("POST");
        req.setPathInfo("/refresh_token");

        try {
            oauthServlet.service(req, res);
            Assert.assertEquals("must return status OK", 200, res.getStatus());
            String tokenJson = res.getContentAsString();
            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && token.get("access_token").getAsString().length() > 0);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }

    }

    @Test
    public void refreshAccessTokenWrongClientHeaderRefreshTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();

        ExternalApplication externalApp = new ExternalApplication();
        externalApp.setAuthor(user1);
        externalApp.setName("Test External Application");
        externalApp.setDescription("This is a test external application");
        externalApp.setRedirectUrl("http://test.url/callback");

        ApplicationUserSession applicationUserSession = new ApplicationUserSession();
        applicationUserSession.setTokens(generateToken(applicationUserSession), generateToken(applicationUserSession));

        ApplicationUserAuthorization applicationUserAuthorization = new ApplicationUserAuthorization(user1, externalApp);
        applicationUserAuthorization.addSession(applicationUserSession);

        externalApp.addApplicationUserAuthorization(applicationUserAuthorization);

        String clientSecret = "fenixedu:fenixedu";
        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(clientSecret.getBytes()));
        req.addParameter(REFRESH_TOKEN, applicationUserSession.getRefreshToken());
        req.addParameter(GRANT_TYPE, GRANT_TYPE_REFRESH_TOKEN);
        req.setMethod("POST");
        req.setPathInfo("/refresh_token");

        try {
            oauthServlet.service(req, res);
            Assert.assertEquals("must return status BAD_REQUEST", 400, res.getStatus());
        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getServiceAccessTokenHeaderEmptyTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();
        String clientSecret = "";

        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encode(clientSecret.getBytes()));
        req.addParameter(GRANT_TYPE, GRANT_TYPE_CLIENT_CREDENTIALS);
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);
            Assert.assertEquals("must return BAD_REQUEST", 400, res.getStatus());

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getServiceAccessTokenHeaderTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authenticate.unmock();
        String clientSecret = serviceApplication.getExternalId() + ":" + serviceApplication.getSecret();
        req.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(clientSecret.getBytes()));
        req.addParameter(GRANT_TYPE, GRANT_TYPE_CLIENT_CREDENTIALS);
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();

            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && token.get("access_token").getAsString().length() > 0);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getServiceAccessTokenTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplication.getExternalId());
        req.addParameter("client_secret", serviceApplication.getSecret());
        req.addParameter("grant_type", "client_credentials");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();

            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && token.get("access_token").getAsString().length() > 0);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testNormalEndpoint() {
        String res = target("bennu-oauth").path("test").path("normal").request().get(String.class);
        Assert.assertEquals("message must be the same", "this is a normal endpoint", res);
    }

    @Test
    public void testServiceOnlyEndpoint() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplication.getExternalId());
        req.addParameter("client_secret", serviceApplication.getSecret());
        req.addParameter("grant_type", "client_credentials");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();

            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();
            final String accessToken = token.get("access_token").getAsString();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && accessToken.length() > 0);

            String result =
                    target("bennu-oauth").path("test").path("service-only-without-scope").queryParam("access_token", accessToken)
                            .request().get(String.class);
            Assert.assertEquals("this is an endpoint with serviceOnly", result);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testServiceOnlyEndpointWithScopeMustFail() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplication.getExternalId());
        req.addParameter("client_secret", serviceApplication.getSecret());
        req.addParameter("grant_type", "client_credentials");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();

            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();
            final String accessToken = token.get("access_token").getAsString();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && accessToken.length() > 0);

            Response result =
                    target("bennu-oauth").path("test").path("service-only-with-scope").queryParam("access_token", accessToken)
                            .request().get(Response.class);

            Assert.assertNotEquals("request must fail", 200, result.getStatus());

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testServiceOnlyWithScopeEndpoint() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplicationWithScope.getExternalId());
        req.addParameter("client_secret", serviceApplicationWithScope.getSecret());
        req.addParameter("grant_type", "client_credentials");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status OK", 200, res.getStatus());

            String tokenJson = res.getContentAsString();

            final JsonObject token = new JsonParser().parse(tokenJson).getAsJsonObject();
            final String accessToken = token.get("access_token").getAsString();

            Assert.assertTrue("response must be a valid json and have access_token field", token.has("access_token")
                    && accessToken.length() > 0);

            String result =
                    target("bennu-oauth").path("test").path("service-only-with-scope").queryParam("access_token", accessToken)
                            .request().get(String.class);
            Assert.assertEquals("this is an endpoint with SERVICE scope, serviceOnly", result);

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getServiceAccessTokenWithWrongGrantTypeTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplication.getExternalId());
        req.addParameter("client_secret", serviceApplication.getSecret());
        req.addParameter("grant_type", "authorization_code");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status BAD_REQUEST", 400, res.getStatus());

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void getServiceAccessTokenWithWrongClientSecretTest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        req.addParameter("client_id", serviceApplication.getExternalId());
        req.addParameter("client_secret",
                BaseEncoding.base64().encode((serviceApplication.getExternalId() + ":lasdlkasldksladkalskdsal").getBytes()));
        req.addParameter("grant_type", "client_credentials");
        req.setMethod("POST");
        req.setPathInfo("/access_token");

        try {
            oauthServlet.service(req, res);

            Assert.assertEquals("must return status BAD_REQUEST", 400, res.getStatus());

        } catch (ServletException | IOException e) {
            Assert.fail(e.getMessage());
        }
    }
}