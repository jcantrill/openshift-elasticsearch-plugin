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

package io.fabric8.elasticsearch.plugin.filter;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.fieldstats.FieldStatsRequest;
import org.elasticsearch.action.fieldstats.FieldStatsResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;

import io.fabric8.elasticsearch.plugin.PluginClient;

/**
 * Filter to modify the response code when an
 * index does not exist.  
 * 
 * This is for the case
 * where we have created an alias but it matches
 * no indexes and the request of '_field_stats?level=indices'
 * causes SG to generate a 403.
 *
 */
public class FieldStatsResponseFilter implements ActionFilter {
    
    public static final String INDICES_FIELD_STATS_READ_ACTION = "indices:data/read/field_stats";
    private static final ESLogger LOGGER = Loggers.getLogger(FieldStatsResponseFilter.class);
    private final PluginClient client;
    
    @Inject
    public FieldStatsResponseFilter(PluginClient client) {
        this.client = client;
    }
    
    @Override
    public int order() {
        return Integer.MIN_VALUE;
    }
    
    @SuppressWarnings("rawtypes")
    @Override
    public  void apply(String action, ActionResponse response, ActionListener listener, ActionFilterChain chain) {
        chain.proceed(action, response, listener);
    }
    
    @SuppressWarnings("rawtypes")
    @Override
    public void apply(final Task task, final String action, final ActionRequest request, final ActionListener listener,
            final ActionFilterChain chain) {
        chain.proceed(task, action, request, new ActionListener<ActionResponse>() {

            @SuppressWarnings("unchecked")
            @Override
            public void onResponse(ActionResponse response) {
                if(INDICES_FIELD_STATS_READ_ACTION.equals(action) && response instanceof FieldStatsResponse) {
                    if(((FieldStatsResponse)response).getIndicesMergedFieldStats().isEmpty()) {
                        LOGGER.trace("Modifying the response to be {}", RestStatus.NO_CONTENT);
                        Throwable err = new ElasticsearchException("The index returned an empty result. "
                                + "You can use the Time Picker to change the time filter or select a higher time interval",
                                RestStatus.NO_CONTENT);
                        
                        listener.onFailure(err);
                        return;
                    }
                }
                listener.onResponse(response);
            }

            @Override
            public void onFailure(Throwable e) {
                LOGGER.trace("Evaluating failure for action '{}' to see if we need to change from a 403", action);
                Throwable err = e;
                if( INDICES_FIELD_STATS_READ_ACTION.equals(action) && request instanceof FieldStatsRequest) {
                    for (String index : ((FieldStatsRequest)request).indices()) {
                        if(!client.indexExists(index)) {
                            LOGGER.trace("Modifying the response to be {}", RestStatus.NOT_FOUND);
                            err = new ElasticsearchException("The index '" + index + "' was not found. This could mean data has not yet been collected.",
                                    RestStatus.NOT_FOUND);
                            break;
                        }
                    }
                }
                listener.onFailure(err);
            }
        });

    }
}
