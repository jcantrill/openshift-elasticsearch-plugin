/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.elasticsearch.plugin;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.elasticsearch.plugin.acl.SearchGuardACL;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.client.DefaultOpenshiftClient;
import io.fabric8.openshift.client.OpenShiftClient;

public class DynamicACLFilter extends RestFilter {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String DEFAULT_SECURITY_CONFIG_INDEX = "searchguard";
	private static final String DEFAULT_AUTH_PROXY_HEADER = "X-Authenticated-User";
	private static final String SEARCHGUARD_TYPE = "ac";
	private static final String SEARCHGUARD_ID = "ac";
	private static final String SEARCHGUARD_AUTHENTICATION_PROXY_HEADER = "searchguard.authentication.proxy.header";
	private static final String SEARCHGUARD_CONFIG_INDEX_NAME = "searchguard.config_index_name";


	private final ObjectMapper mapper = new ObjectMapper();
	private final ESLogger logger;
	private final UserProjectCache cache;
	private final String proxyUserHeader;
	private final Client esClient;
	private final String searchGuardIndex;

	public DynamicACLFilter(final UserProjectCache cache, final Settings settings, final Client client, final ESLogger logger){
		this.cache = cache;
		this.logger = logger;
		this.esClient = client;
		this.proxyUserHeader = settings.get(SEARCHGUARD_AUTHENTICATION_PROXY_HEADER, DEFAULT_AUTH_PROXY_HEADER);
		this.searchGuardIndex = settings.get(SEARCHGUARD_CONFIG_INDEX_NAME, DEFAULT_SECURITY_CONFIG_INDEX);
		
		logger.debug("searchGuardIndex: {}", this.searchGuardIndex);
	}
	
	@Override
	public void process(RestRequest request, RestChannel channel, RestFilterChain chain) throws Exception {
		try {
			logger.debug("Handling Request in SearchGuard Sync filter...");
			final String user = getUser(request);
			final String token = getBearerToken(request);
			logger.debug("Evaluating OpenShift SearchGuard Sync filter for user '{}' with a {} token", user,
					(StringUtils.isNotEmpty(token) ? "non-empty" : "empty"));
			logger.debug("Cache has user: {}", cache.hasUser(user));
			if (StringUtils.isNotEmpty(token) && StringUtils.isNotEmpty(user) && !cache.hasUser(user)) {
				if(updateCache(user, token)){
					syncAcl();
				}
			}

		} catch (Exception e) {
			logger.error("Error handling request in OpenShift SearchGuard filter", e);
		} finally {
			chain.continueProcessing(request, channel);
		}
	}

	private String getUser(RestRequest request) {
		return (String) ObjectUtils.defaultIfNull(request.header(proxyUserHeader), "");
	}

	private String getBearerToken(RestRequest request) {
		final String[] auth = ((String) ObjectUtils.defaultIfNull(request.header(AUTHORIZATION_HEADER), "")).split(" ");
		if (auth.length >= 2 && "Bearer".equals(auth[0])) {
			return auth[1];
		}
		return "";
	}
	

	private boolean updateCache(final String user, final String token) {
		logger.debug("Updating the cache for user '{}'", user);
		
		try{
			Set<String> projects = listProjectsFor(token);
			cache.update(user, projects);
		} catch (Exception e) {
			logger.error("Error retrieving project list for {}",e, user);
			return false;
		}
		return true;
	}
	
	private Set<String> listProjectsFor(final String token) throws Exception{
		ConfigBuilder builder = new ConfigBuilder()
				.withOauthToken(token);
		Set<String> names = new HashSet<>();
		try(OpenShiftClient client = new DefaultOpenshiftClient(builder.build())){
			List<Project> projects = client.projects().list().getItems();
			for (Project project : projects) {
				names.add(project.getMetadata().getName());
			}
		}
		return names;
	}
	
	private synchronized void syncAcl() {
		logger.debug("Syncing the ACL to ElasticSearch");
		try {
			logger.debug("Loading SearchGuard ACL..");
			final SearchGuardACL acl = loadAcl(esClient);
			logger.debug("Syncing from cache to ACL");
			acl.syncFrom(cache);
			write(esClient, acl);
		} catch (Exception e) {
			logger.error("Exception why syncing ACL with cache", e);
		}
	}
	
	private SearchGuardACL loadAcl(Client esClient) throws IOException {
		GetResponse response = esClient.prepareGet(searchGuardIndex, SEARCHGUARD_TYPE, SEARCHGUARD_ID)
				.setRefresh(true)
				.execute()
				.actionGet(); // need to worry about timeout?
		return mapper.readValue(response.getSourceAsBytes(), SearchGuardACL.class);
	}
	

	private void write(Client esClient, SearchGuardACL acl) throws JsonProcessingException, InterruptedException {
		if (logger.isDebugEnabled()) {
			logger.debug("Writing ACLs {}", mapper.writer(new DefaultPrettyPrinter()).writeValueAsString(acl));
		}
		esClient.prepareUpdate(searchGuardIndex, SEARCHGUARD_TYPE, SEARCHGUARD_ID).setDoc(mapper.writeValueAsBytes(acl))
			.setRefresh(true)
			.execute();
		/*
		 * TODO Replace with ActionFilter and thread suspension
		 * Allow searchguard to sync ACL
		 */
		Thread.sleep(2500); //1 sec for ES & 1 sec for SG

	}

	@Override
	public int order() {
		// need to run before search guard
		return Integer.MIN_VALUE;
	}
	
}
