package io.onedev.server.plugin.sso.openid;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.wicket.Session;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.flow.RedirectToUrlException;
import javax.validation.constraints.NotEmpty;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.common.contenttype.ContentType;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.SerializeException;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoErrorResponse;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.sso.SsoAuthenticated;
import io.onedev.server.model.support.administration.sso.SsoConnector;
import io.onedev.server.util.OAuthUtils;
import io.onedev.server.util.validation.annotation.UrlSegment;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Password;
import io.onedev.server.web.page.admin.ssosetting.SsoProcessPage;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

@Editable(name="OpenID", order=10000, description="Refer to this <a href='$docRoot/pages/okta-sso.md' target='_blank'>usage scenario</a> for an example setup")
public class OpenIdConnector extends SsoConnector {

	private static final long serialVersionUID = 1L;
	
	private static final Logger logger = LoggerFactory.getLogger(OpenIdConnector.class);

	private static final String SESSION_ATTR_PROVIDER_METADATA = "endpoints";
	
	private static final String SESSION_ATTR_STATE = "state";
	
	private String clientId;
	
	private String clientSecret;
	
	private String issuerUrl;
	
	private String groupsClaim;
	
	private String buttonImageUrl = "https://openid.net/images/logo/openid-icon-100x100.png";
	
	@Override
	public boolean isManagingMemberships() {
		return getGroupsClaim() != null;
	}

	@Editable(order=100, description="Name of the provider will serve two purpose: "
			+ "<ul>"
			+ "<li>Display on login button"
			+ "<li>Form the authorization callback url which will be <i>&lt;server url&gt;/" + SsoProcessPage.MOUNT_PATH + "/" + SsoProcessPage.STAGE_CALLBACK + "/&lt;name&gt;</i>"
			+ "</ul>")
	@UrlSegment // will be used as part of callback url
	@NotEmpty
	@Override
	public String getName() {
		return super.getName();
	}

	@Override
	public void setName(String name) {
		super.setName(name);
	}
	
	@Editable(order=200, description="Specify issuer url of your OpenID provider. OpenID endpoints "
			+ "discovery url will be constructed from this by appending <i>/.well-known/openid-configuration</i>. "
			+ "Make sure to use HTTPS protocol as OneDev relies on TLS encryption to ensure "
			+ "token validity")
	@NotEmpty
	public String getIssuerUrl() {
		return issuerUrl;
	}

	public void setIssuerUrl(String issuerUrl) {
		this.issuerUrl = issuerUrl;
	}

	@Editable(order=1000, description="OpenID client identification will be assigned by your OpenID "
			+ "provider when registering this OneDev instance as client application")
	@NotEmpty
	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	@Editable(order=1100, description="OpenID client secret will be generated by your OpenID "
			+ "provider when registering this OneDev instance as client application")
	@Password
	@NotEmpty
	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	@Editable(order=1500, description="Optionally specify the OpenID claim to retrieve groups "
			+ "of authenticated user")
	public String getGroupsClaim() {
		return groupsClaim;
	}

	public void setGroupsClaim(String groupsClaim) {
		this.groupsClaim = groupsClaim;
	}

	@Editable(order=19100, description="Specify image on the login button")
	@NotEmpty
	@Override
	public String getButtonImageUrl() {
		return buttonImageUrl;		
	}

	public void setButtonImageUrl(String buttonImageUrl) {
		this.buttonImageUrl = buttonImageUrl;
	}

	@Override
	public SsoAuthenticated processLoginResponse() {
		HttpServletRequest request = (HttpServletRequest) RequestCycle.get().getRequest().getContainerRequest();
		try {
			AuthenticationResponse authenticationResponse = AuthenticationResponseParser.parse(
					new URI(request.getRequestURI() + "?" + request.getQueryString()));
			if (authenticationResponse instanceof AuthenticationErrorResponse) {
				throw buildException(authenticationResponse.toErrorResponse().getErrorObject()); 
			} else {
				AuthenticationSuccessResponse authenticationSuccessResponse = authenticationResponse.toSuccessResponse();
				
				String state = (String) Session.get().getAttribute(SESSION_ATTR_STATE);
				
				if (state == null || !state.equals(authenticationSuccessResponse.getState().getValue()))
					throw new AuthenticationException("Unsolicited OIDC authentication response");
				
				AuthorizationGrant codeGrant = new AuthorizationCodeGrant(
						authenticationSuccessResponse.getAuthorizationCode(), getCallbackUri());

				ClientID clientID = new ClientID(getClientId());
				Secret clientSecret = new Secret(getClientSecret());
				ClientAuthentication clientAuth = new ClientSecretBasic(clientID, clientSecret);
				TokenRequest tokenRequest = new TokenRequest(
						new URI(getCachedProviderMetadata().getTokenEndpoint()), clientAuth, codeGrant);
				
				HTTPRequest httpRequest = tokenRequest.toHTTPRequest();
				httpRequest.setAccept(ContentType.APPLICATION_JSON.toString());
				TokenResponse tokenResponse = OIDCTokenResponseParser.parse(httpRequest.send());
				
				if (tokenResponse.indicatesSuccess()) 
					return processTokenResponse((OIDCTokenResponse)tokenResponse.toSuccessResponse());
				else 
					throw buildException(tokenResponse.toErrorResponse().getErrorObject());
			}
		} catch (ParseException | URISyntaxException|SerializeException|IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private String getStringValue(Object jsonValue) {
		if (jsonValue instanceof String) {
			return (String) jsonValue;
		} else {
			JSONArray emailArray = (JSONArray) jsonValue;
			if (!emailArray.isEmpty())
				return (String) emailArray.iterator().next();
			else
				return null;
		}
	}
	
	protected SsoAuthenticated processTokenResponse(OIDCTokenResponse tokenResponse) {
		try {
			JWT idToken = tokenResponse.getOIDCTokens().getIDToken();
			JWTClaimsSet claims = idToken.getJWTClaimsSet();
			
			if (!claims.getIssuer().equals(getCachedProviderMetadata().getIssuer()))
				throw new AuthenticationException("Inconsistent issuer in provider metadata and ID token");
			
			DateTime now = new DateTime();
			
			if (claims.getIssueTime() != null && claims.getIssueTime().after(now.plusSeconds(10).toDate()))
				throw new AuthenticationException("Invalid issue date of ID token");
			
			if (claims.getExpirationTime() != null && now.toDate().after(claims.getExpirationTime()))
				throw new AuthenticationException("ID token was expired");

			String subject = claims.getSubject();
			
			BearerAccessToken accessToken = tokenResponse.getOIDCTokens().getBearerAccessToken();

			UserInfoRequest userInfoRequest = new UserInfoRequest(
					new URI(getCachedProviderMetadata().getUserInfoEndpoint()), accessToken);
			HTTPResponse httpResponse = userInfoRequest.toHTTPRequest().send();

			if (httpResponse.getStatusCode() == HTTPResponse.SC_OK) {
				JSONObject json = httpResponse.getContentAsJSONObject();
				if (!subject.equals(json.get("sub")))
					throw new AuthenticationException("OIDC error: Inconsistent sub in ID token and userinfo");
				
				String email = getStringValue(json.get("email"));
				if (StringUtils.isBlank(email))
					throw new AuthenticationException("OIDC error: No email claim returned");
				
				String userName = getStringValue(json.get("preferred_username"));
				if (StringUtils.isBlank(userName))
					userName = email;
				userName = StringUtils.substringBefore(userName, "@");
				
				String fullName = getStringValue(json.get("name"));

				List<String> groupNames;
				if (getGroupsClaim() != null) {
					groupNames = new ArrayList<>();
					JSONArray jsonArray = (JSONArray) json.get(getGroupsClaim());
					if (jsonArray != null) {
						for (Object group: jsonArray)
							groupNames.add((String) group);
					}
				} else {
					groupNames = null;
				}
				
				return new SsoAuthenticated(userName, email, fullName, groupNames, null, this);
			} else {
				throw buildException(UserInfoErrorResponse.parse(httpResponse).getErrorObject());
			}
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		}
	}
	
	protected RuntimeException buildException(ErrorObject error) {
		return new AuthenticationException(OAuthUtils.getErrorMessage(error));
	}

	@Override
	public void initiateLogin() {
		try {
			ClientID clientID = new ClientID(getClientId());
			
			State state = new State("OIDC-" + UUID.randomUUID().toString());
			Session.get().setAttribute(SESSION_ATTR_STATE, state.getValue());
			Session.get().setAttribute(SESSION_ATTR_PROVIDER_METADATA, discoverProviderMetadata());
			
			String scopes = "openid email profile";
			if (getGroupsClaim() != null)
				scopes = scopes + " " + getGroupsClaim();
			
			AuthenticationRequest request = new AuthenticationRequest(
					new URI(getCachedProviderMetadata().getAuthorizationEndpoint()),
				    new ResponseType("code"), Scope.parse(scopes), clientID, getCallbackUri(),
				    state, new Nonce());
			throw new RedirectToUrlException(request.toURI().toString());
		} catch (URISyntaxException|SerializeException e) {
			throw new RuntimeException(e);
		}		
	}
	
	protected ProviderMetadata discoverProviderMetadata() {
		try {
			JsonNode json = OneDev.getInstance(ObjectMapper.class).readTree(
					new URI(getIssuerUrl() + "/.well-known/openid-configuration").toURL());
			return new ProviderMetadata(
					json.get("issuer").asText(),
					json.get("authorization_endpoint").asText(),
					json.get("token_endpoint").asText(), 
					json.get("userinfo_endpoint").asText());
		} catch (IOException | URISyntaxException e) {
			if (e.getMessage() != null) {
				logger.error("Error discovering OIDC metadata", e);
				throw new AuthenticationException(e.getMessage());
			} else {
				throw new RuntimeException(e);
			}
		}
		
	}
	
	protected ProviderMetadata getCachedProviderMetadata() {
		ProviderMetadata metadata = (ProviderMetadata) Session.get().getAttribute(SESSION_ATTR_PROVIDER_METADATA);
		if (metadata == null)
			throw new AuthenticationException("Unsolicited OIDC response");
		return metadata;
	}
	
	@Override
	public URI getCallbackUri() {
		String serverUrl = OneDev.getInstance(SettingManager.class).getSystemSetting().getServerUrl();
		try {
			return new URI(serverUrl + "/" + SsoProcessPage.MOUNT_PATH + "/" 
					+ SsoProcessPage.STAGE_CALLBACK + "/" + getName());
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

}
