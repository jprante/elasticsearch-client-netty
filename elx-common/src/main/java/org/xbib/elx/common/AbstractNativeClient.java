package org.xbib.elx.common;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthAction;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateAction;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsAction;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsAction;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequestBuilder;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsAction;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.xbib.elx.api.NativeClient;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractNativeClient implements NativeClient {

    private static final Logger logger = LogManager.getLogger(AbstractNativeClient.class.getName());

    /**
     * The one and only index type name used in the extended client.
     * Notr that all Elasticsearch version < 6.2.0 do not allow a prepending "_".
     */
    protected static final String TYPE_NAME = "doc";

    protected ElasticsearchClient client;

    protected final AtomicBoolean closed;

    protected AbstractNativeClient() {
        closed = new AtomicBoolean(false);
    }

    @Override
    public void setClient(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public ElasticsearchClient getClient() {
        return client;
    }

    protected abstract ElasticsearchClient createClient(Settings settings) throws IOException;

    protected abstract void closeClient() throws IOException;


    @Override
    public void init(Settings settings) throws IOException {
        logger.log(Level.INFO, "initializing with settings = " + settings.toDelimitedString(','));
        if (client == null) {
            client = createClient(settings);
        }
    }

    @Override
    public String getClusterName() {
        ensureClientIsPresent();
        try {
            ClusterStateRequest clusterStateRequest = new ClusterStateRequest().clear();
            ClusterStateResponse clusterStateResponse =
                    getClient().execute(ClusterStateAction.INSTANCE, clusterStateRequest).actionGet();
            return clusterStateResponse.getClusterName().value();
        } catch (ElasticsearchTimeoutException e) {
            logger.warn(e.getMessage(), e);
            return "TIMEOUT";
        } catch (NoNodeAvailableException e) {
            logger.warn(e.getMessage(), e);
            return "DISCONNECTED";
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            return "[" + e.getMessage() + "]";
        }
    }

    @Override
    public void waitForCluster(String statusString, long maxWaitTime, TimeUnit timeUnit) {
        ensureClientIsPresent();
        ClusterHealthStatus status = ClusterHealthStatus.fromString(statusString);
        TimeValue timeout = toTimeValue(maxWaitTime, timeUnit);
        ClusterHealthResponse healthResponse = client.execute(ClusterHealthAction.INSTANCE,
                new ClusterHealthRequest().timeout(timeout).waitForStatus(status)).actionGet();
        if (healthResponse != null && healthResponse.isTimedOut()) {
            String message = "timeout, cluster state is " + healthResponse.getStatus().name() + " and not " + status.name();
            if (logger.isErrorEnabled()) {
                logger.error(message);
            }
            throw new IllegalStateException(message);
        }
    }

    @Override
    public String getHealthColor(long maxWaitTime, TimeUnit timeUnit) {
        ensureClientIsPresent();
        try {
            TimeValue timeout = toTimeValue(maxWaitTime, timeUnit);
            ClusterHealthResponse healthResponse = client.execute(ClusterHealthAction.INSTANCE,
                    new ClusterHealthRequest().timeout(timeout)).actionGet();
            ClusterHealthStatus status = healthResponse.getStatus();
            return status.name();
        } catch (ElasticsearchTimeoutException e) {
            logger.warn(e.getMessage(), e);
            return "TIMEOUT";
        } catch (NoNodeAvailableException e) {
            logger.warn(e.getMessage(), e);
            return "DISCONNECTED";
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            return "[" + e.getMessage() + "]";
        }
    }

    @Override
    public Map<String, ?> getMapping(String index, String mapping) {
        GetMappingsRequestBuilder getMappingsRequestBuilder = new GetMappingsRequestBuilder(client, GetMappingsAction.INSTANCE)
                .setIndices(index)
                .setTypes(mapping);
        GetMappingsResponse getMappingsResponse = getMappingsRequestBuilder.execute().actionGet();
        logger.info("get mappings response = {}", getMappingsResponse.getMappings().get(index).get(mapping).getSourceAsMap());
        return getMappingsResponse.getMappings().get(index).get(mapping).getSourceAsMap();
    }

    @Override
    public long getSearchableDocs(String index) {
        SearchRequestBuilder searchRequestBuilder = new SearchRequestBuilder(client, SearchAction.INSTANCE)
                .setIndices(index)
                .setQuery(QueryBuilders.matchAllQuery())
                .setSize(0);
        return searchRequestBuilder.execute().actionGet().getHits().getTotalHits();
    }

    @Override
    public boolean isIndexExists(String index) {
        IndicesExistsRequest indicesExistsRequest = new IndicesExistsRequest();
        indicesExistsRequest.indices(index);
        IndicesExistsResponse indicesExistsResponse =
                client.execute(IndicesExistsAction.INSTANCE, indicesExistsRequest).actionGet();
        return indicesExistsResponse.isExists();
    }


    @Override
    public void close() throws IOException {
        ensureClientIsPresent();
        if (closed.compareAndSet(false, true)) {
            closeClient();
        }
    }

    protected void updateIndexSetting(String index, String key, Object value, long timeout, TimeUnit timeUnit) throws IOException {
        ensureClientIsPresent();
        if (index == null) {
            throw new IOException("no index name given");
        }
        if (key == null) {
            throw new IOException("no key given");
        }
        if (value == null) {
            throw new IOException("no value given");
        }
        Settings.Builder updateSettingsBuilder = Settings.builder();
        updateSettingsBuilder.put(key, value.toString());
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(index)
                .settings(updateSettingsBuilder).timeout(toTimeValue(timeout, timeUnit));
        client.execute(UpdateSettingsAction.INSTANCE, updateSettingsRequest).actionGet();
    }

    protected void ensureClientIsPresent() {
        if (this instanceof MockAdminClient) {
            return;
        }
        if (client == null) {
            throw new IllegalStateException("no client");
        }
    }

    protected static TimeValue toTimeValue(long timeValue, TimeUnit timeUnit) {
        switch (timeUnit) {
            case DAYS:
                return TimeValue.timeValueHours(24 * timeValue);
            case HOURS:
                return TimeValue.timeValueHours(timeValue);
            case MINUTES:
                return TimeValue.timeValueMinutes(timeValue);
            case SECONDS:
                return TimeValue.timeValueSeconds(timeValue);
            case MILLISECONDS:
                return TimeValue.timeValueMillis(timeValue);
            case MICROSECONDS:
                return TimeValue.timeValueNanos(1000 * timeValue);
            case NANOSECONDS:
                return TimeValue.timeValueNanos(timeValue);
            default:
                throw new IllegalArgumentException("unknown time unit: " + timeUnit);
        }
    }

}
