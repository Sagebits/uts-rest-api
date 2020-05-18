/*
 * Copyright 2018 VetsEZ Inc, Sagebits LLC
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
 * 
 * Contributions from 2015-2017 where performed either by US government
 * employees, or under US Veterans Health Administration contracts.
 *
 * US Veterans Health Administration contributions by government employees
 * are work of the U.S. Government and are not subject to copyright
 * protection in the United States. Portions contributed by government
 * employees are USGovWork (17USC §105). Not subject to copyright.
 * 
 * Contribution by contractors to the US Veterans Health Administration
 * during this period are contractually contributed under the
 * Apache License, Version 2.0.
 *
 * See: https://www.usa.gov/government-works
 */

package net.sagebits.tmp.isaac.rest.session;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.util.Pair;
import net.sagebits.tmp.isaac.rest.api.exceptions.RestException;
import net.sagebits.tmp.isaac.rest.tokens.EditToken;
import net.sagebits.uts.auth.data.SSOToken;
import net.sagebits.uts.auth.rest.api.exceptions.RestExceptionResponse;
import net.sagebits.uts.auth.rest.api1.data.RestUser;
import net.sagebits.uts.auth.rest.session.AuthRequestParameters;
import sh.isaac.api.Get;
import sh.isaac.api.LookupService;

/**
 * 
 * {@link RestUserServiceAuthIntegrated}
 *
 * This implementation of the {@link RestUserService} defers all user lookup and role information to the
 * uts-auth-api codebase. It reads users via rest, and has no ability to create users that don't yet exist.
 * 
 * It does, however, create concepts in the local database that correspond to the users accessing the system,
 * and maintains a cache of user information, so that lookups to the uts-auth-api only occur every so often.
 *
 * @author <a href="mailto:daniel.armbrust.list@gmail.com">Dan Armbrust</a>
 */

public class RestUserServiceAuthIntegrated implements RestUserService
{
	private static Logger log = LogManager.getLogger(RestUserServiceAuthIntegrated.class);
	
	private static final long userRoleMaxAge = 1000l * 60l * 5l;  // recheck roles every 5 minutes, but the auth server also now invalidates roles via the clear call.
	private static final long cleanUserCache = 1000l * 60l * 30l;  // clear any cached info after 30 minutes  
	
	private static final String ANON_TOKEN = "ANON_TOKEN";
	
	private final UUID dbID;
	private final String remoteAuthURL;

	//Map of SSOToken to a RestUser - and the time stamp that we read that user (and roles)
	private ConcurrentHashMap<String, Pair<RestUser, Long>> ssoTokenCache = new ConcurrentHashMap<String, Pair<RestUser, Long>>(100);

	protected RestUserServiceAuthIntegrated()
	{
		// for RestServiceSelector
		dbID = Get.dataStore().getDataStoreId().get();
		String temp = RestConfig.getInstance().getAuthURL();
		if (temp != null && temp.length() > 1 && temp.endsWith("/"))
		{
			temp = temp.substring(0, temp.length() - 1);
		}
		remoteAuthURL = temp;
		
		if (StringUtils.isBlank(remoteAuthURL))
		{
			throw new RuntimeException("Misconfigured - Remote rest service can't run without a remote url");
		}
		else if (!remoteAuthURL.toLowerCase().startsWith("https"))
		{
			log.error("remoteAuthURL should be configured with SSL!");
		}
		Get.workExecutors().getScheduledThreadPoolExecutor().scheduleAtFixedRate(() -> {expireOldUsers();}, 15, 15, TimeUnit.MINUTES);
	}
	

	/**
	 * @see net.sagebits.tmp.isaac.rest.session.RestUserService#getUser(Map, EditToken, Function)
	 */
	@Override
	public Optional<RestUser> getUser(Map<String, List<String>> requestParameters, EditToken editToken, Function<String, Optional<String>> cookieValueProvider) throws RestException
	{
		Optional<RestUser> restUser = Optional.empty();
		if (requestParameters.containsKey(AuthRequestParameters.ssoToken) || requestParameters.containsKey(AuthRequestParameters.serviceToken) 
				|| cookieValueProvider.apply(SSOToken.cookieName).isPresent())
		{
			String token = null;
			try
			{
				String tokenType;
				if (StringUtils.isNotBlank(RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.ssoToken)))
				{
					token = RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.ssoToken);
					tokenType = AuthRequestParameters.ssoToken;
				}
				else if (StringUtils.isNotBlank(cookieValueProvider.apply(SSOToken.cookieName).orElse("")))
				{
					token = cookieValueProvider.apply(SSOToken.cookieName).get();
					tokenType = AuthRequestParameters.ssoToken;
				}
				else if (StringUtils.isNotBlank(RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.serviceToken)))
				{
					token = RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.serviceToken);
					tokenType = AuthRequestParameters.serviceToken;
				}
				else
				{
					throw new RuntimeException("Internal error");
				}

				Pair<RestUser, Long> userInfo = ssoTokenCache.get(token);
				if (isCachedTokenValid(userInfo, token, tokenType))
				{
					restUser = Optional.ofNullable(userInfo.getKey());
				}
				
				//don't have user info, or its outdated / wrong.  Need to request.
				if (!restUser.isPresent())
				{
					restUser = getUser(token, tokenType);
					if (restUser.isPresent())
					{
						ssoTokenCache.put(restUser.get().ssoToken, new Pair<>(restUser.get(), System.currentTimeMillis()));
						
						//Note that this read always returns a NEW ssoToken, but this new ssoToken isn't returned to the caller here (in most calls)
						//Their future requests will likely still be using the same token they just passed us (which was valid)
						//So also cache the token they passed, but with the up-to-date user roles / info we just read.
						ssoTokenCache.put(token, new Pair<>(restUser.get(), System.currentTimeMillis()));
					}
				}
			}
			catch (RestException e)
			{
				throw e;
			}
			catch (SecurityException e)
			{
				if (token != null)
				{
					ssoTokenCache.remove(token);
				}
				throw e;
			}
			catch (Exception e)
			{
				log.error("Unexpected error", e);
				throw new RestException("Internal error");
			}
		}
		
		if (!restUser.isPresent() && (requestParameters.containsKey(AuthRequestParameters.userName) || requestParameters.containsKey(AuthRequestParameters.email)))
		{
			if (!requestParameters.containsKey(AuthRequestParameters.password))
			{
				throw new RestException("If 'userName' or 'email' is provided, then 'password' must be provided as well");
			}
			//No cache for uid / pw - just pass it thru
			restUser = getUser(RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.userName), 
					RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.email), 
					RequestInfoUtils.getFirstParameterValue(requestParameters, AuthRequestParameters.password), true);
			if (restUser.isPresent())
			{
				ssoTokenCache.put(restUser.get().ssoToken, new Pair<>(restUser.get(), System.currentTimeMillis()));
			}
		}
		
		if (!restUser.isPresent())
		{
			//see if the admin service allows anonymous read
			Pair<RestUser, Long> userInfo = ssoTokenCache.get(ANON_TOKEN);
			if (isCachedTokenValid(userInfo, ANON_TOKEN, AuthRequestParameters.ssoToken))
			{
				restUser = Optional.ofNullable(userInfo.getKey());
			}
			else
			{
				restUser = getUser("", "", "", false);
				if (restUser.isPresent())
				{
					ssoTokenCache.put(ANON_TOKEN, new Pair<>(restUser.get(), System.currentTimeMillis()));
				}
			}
		}
		
		if (restUser.isPresent())
		{
			//Make sure that a concept exists for the user.
			getAuthorNid(restUser.get());
			
			if (editToken != null)
			{
				editToken.setUser(restUser.get());
			}
		}
		
		return restUser;
	}
	
	private boolean isCachedTokenValid(Pair<RestUser, Long> cachedUserInfo, String token, String tokenType)
	{
		if (cachedUserInfo != null)
		{
			log.debug("SSOToken cache hit for token " + tokenType);
			if ((System.currentTimeMillis() - cachedUserInfo.getValue()) > userRoleMaxAge)
			{
				log.debug("User cache hit, but roles expired - last read at {} now {} - requesting user info", cachedUserInfo.getValue(), System.currentTimeMillis());
				return false;
			}
			else 
			{
				//If the token was given with a UUID, it has to match our UUID.  Its ok if the token didn't have a UUID.
				if (cachedUserInfo.getKey().dbUUID != null && dbID != null && !dbID.equals(cachedUserInfo.getKey().dbUUID))
				{
					log.debug("User cache hit, but roles were for db {} while we are running {} - requesting user info", cachedUserInfo.getKey().dbUUID, dbID);
					return false;
				}
				else
				{
					log.debug("User cache hit, roles still valid, using cached user and roles");
					return true;
				}
			}
		}
		else
		{
			log.debug("SSOToken cache miss for token " + tokenType);
		}
		return false;
	}

	private void expireOldUsers()
	{
		try
		{
			if (ssoTokenCache.size() == 0)
			{
				//Just be quiet and return
				return;
			}
			log.debug("Expiring out-of-date users - size before: " + ssoTokenCache.size());
			Iterator<Entry<String, Pair<RestUser, Long>>> x = ssoTokenCache.entrySet().iterator();
			while (x.hasNext())
			{
				if ((System.currentTimeMillis() - x.next().getValue().getValue()) > cleanUserCache)
				{
					x.remove();
				}
			}
			log.debug("Finished expiring unused users - size after: " + ssoTokenCache.size());
		}
		catch (Exception e)
		{
			log.error("Unexpected error expiring unused tokens", e);
		}
	}

	private Optional<RestUser> getUser(String userName, String email, String password, boolean throwAuthError) throws RestException
	{
		try
		{
			Map<String, String> params = new HashMap<>();
			if (StringUtils.isNotBlank(userName))
			{
				params.put("userName", userName);
			}
			if (StringUtils.isNotBlank(email))
			{
				params.put("email", email);
			}
			params.put("password", password);
			if (dbID != null)
			{
				params.put("dbUUID", dbID.toString());
			}
			log.debug("Sending credentials for '{}' '{}', and DB '{}'", userName, email, dbID);
			
			URL url = new URL(remoteAuthURL + "/1/auth/user");
			
			try
			{
				String jsonResultString = remoteRequestJSON(url, params);
				return Optional.of(deserializeRestUser(jsonResultString));
			}
			catch (RestException | SecurityException e)
			{
				if (throwAuthError)
				{
					throw e;
				}
				else
				{
					return Optional.empty();
				}
			}
		}
		catch (MalformedURLException e)
		{
			log.error("Misconfigured remote auth", e);
			throw new RuntimeException("Misconfigured remote authentication");
		}
	}

	/**
	 * Get the user from the uts-auth-api server
	 * @param token
	 * @param tokenTypeParam
	 * @return
	 * @throws RestException
	 */
	private Optional<RestUser> getUser(String token, String tokenTypeParam) throws RestException, SecurityException
	{
		try
		{
			Map<String, String> params = new HashMap<>();
			params.put(tokenTypeParam, token);
			if (dbID != null)
			{
				params.put("dbUUID", dbID.toString());
			}
			log.debug("Sending token type '{}' and DB '{}'", tokenTypeParam, dbID);
			
			URL url = new URL(remoteAuthURL + "/1/auth/user");
			
			String jsonResultString = remoteRequestJSON(url, params);
			return Optional.of(deserializeRestUser(jsonResultString));
		}
		catch (MalformedURLException e)
		{
			log.error("Misconfigured remote auth", e);
			throw new RuntimeException("Misconfigured remote authentication");
		}
	}
	
	private RestUser deserializeRestUser(String jsonString)
	{
		try
		{
			ObjectMapper om = new ObjectMapper();
			return (RestUser)om.readerFor(RestUser.class).readValue(jsonString);
		}
		catch (Exception e)
		{
			log.error("Mimatched return from auth server", e);
			throw new RuntimeException("Misconfigured system");
		}
	}
	
	private String getTargetFromUrl(URL url)
	{
		try
		{
			StringBuilder target = new StringBuilder();
			target.append(url.getProtocol());
			target.append("://");
			target.append(url.getHost());
			if (url.getPort() > 0)
			{
				target.append(":" + url.getPort());
			}

			return target.toString();
		}
		catch (RuntimeException e)
		{
			log.error("FAILED getting target from URL '" + url + "'", e);
			throw e;
		}
	}
	
	private String remoteRequestJSON(URL url, Map<String, String> params) throws RestException, SecurityException
	{
		ClientService clientService = LookupService.getService(ClientService.class);
		WebTarget target = clientService.getClient().target(getTargetFromUrl(url));
		target = target.path(url.getPath());
		
		for (Map.Entry<String, String> entry : params.entrySet())
		{
			target = target.queryParam(entry.getKey(), entry.getValue());
		}
		Response response = target.request().accept(MediaType.APPLICATION_JSON).get();

		
		if (response.getStatus() == Status.OK.getStatusCode())
		{
			log.info("Auth request returned ok");
		}
		else if (response.getStatus() == Status.UNAUTHORIZED.getStatusCode() || response.getStatus() == Status.FORBIDDEN.getStatusCode())
		{
			RestExceptionResponse errorResponse = response.readEntity(RestExceptionResponse.class);
			log.debug("User unauthorized {}", errorResponse.conciseMessage);
			throw new SecurityException("Unauthorized - " + errorResponse.conciseMessage);
		}
		else
		{
			log.error("Failed performing GET " + url.toString() + " with CODE=" + response.getStatus() + " and REASON=" + response.getStatusInfo());
			throw new SecurityException("Unable to read user from auth service");
		}
		
		String responseJson = response.readEntity(String.class);
		log.trace("Request '{}' returned '{}'", url.toString(), responseJson);
		return responseJson;
	}

	@Override
	public void clearCache()
	{
		ssoTokenCache.clear();
		log.debug("token cache cleared");
	}
}
