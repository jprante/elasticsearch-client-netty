package org.xbib.elasticsearch.extras.client.transport;

import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.Before;
import org.junit.Test;
import org.xbib.elasticsearch.NodeTestBase;
import org.xbib.elasticsearch.extras.client.ClientBuilder;
import org.xbib.elasticsearch.extras.client.SimpleBulkControl;
import org.xbib.elasticsearch.extras.client.SimpleBulkMetric;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 *
 */
public class BulkTransportClientTest extends NodeTestBase {

    private static final Long MAX_ACTIONS = 1000L;

    private static final Long NUM_ACTIONS = 1234L;

    @Before
    public void startNodes() {
        try {
            super.startNodes();
            startNode("2");
        } catch (Throwable t) {
            logger.error("startNodes failed", t);
        }
    }

    @Test
    public void testBulkClientIndexCreation() throws IOException {
        logger.info("firing up BulkTransportClient");
        final BulkTransportClient client = ClientBuilder.builder()
                .put(getClientSettings())
                .put(ClientBuilder.FLUSH_INTERVAL, TimeValue.timeValueSeconds(60))
                .setMetric(new SimpleBulkMetric())
                .setControl(new SimpleBulkControl())
                .toBulkTransportClient();
        logger.info("creating index");
        client.newIndex("test");
        if (client.hasThrowable()) {
            logger.error("error", client.getThrowable());
        }
        assertFalse(client.hasThrowable());
        try {
            logger.info("deleting/creating index sequence start");
            client.deleteIndex("test")
                    .newIndex("test")
                    .deleteIndex("test");
            logger.info("deleting/creating index sequence end");
        } catch (NoNodeAvailableException e) {
            logger.error("no node available");
        } finally {
            if (client.hasThrowable()) {
                logger.error("error", client.getThrowable());
            }
            assertFalse(client.hasThrowable());
            client.shutdown();
        }
    }

    @Test
    public void testSingleDocBulkClient() throws IOException {
        logger.info("firing up BulkTransportClient");
        final BulkTransportClient client = ClientBuilder.builder()
                .put(getClientSettings())
                .put(ClientBuilder.MAX_ACTIONS_PER_REQUEST, MAX_ACTIONS)
                .put(ClientBuilder.FLUSH_INTERVAL, TimeValue.timeValueSeconds(60))
                .setMetric(new SimpleBulkMetric())
                .setControl(new SimpleBulkControl())
                .toBulkTransportClient();
        try {
            logger.info("creating index");
            client.newIndex("test");
            logger.info("indexing one doc");
            client.index("test", "test", "1", "{ \"name\" : \"Hello World\"}"); // single doc ingest
            logger.info("flush");
            client.flushIngest();
            logger.info("wait for responses");
            client.waitForResponses(TimeValue.timeValueSeconds(30));
            logger.info("waited for responses");
        } catch (InterruptedException e) {
            // ignore
        } catch (ExecutionException e) {
            logger.error(e.getMessage(), e);
        } catch (NoNodeAvailableException e) {
            logger.warn("skipping, no node available");
        } finally {
            assertEquals(1, client.getMetric().getSucceeded().getCount());
            if (client.hasThrowable()) {
                logger.error("error", client.getThrowable());
            }
            assertFalse(client.hasThrowable());
            client.shutdown();
        }
    }

    @Test
    public void testRandomDocsBulkClient() {
        long numactions = NUM_ACTIONS;
        final BulkTransportClient client = ClientBuilder.builder()
                .put(getClientSettings())
                .put(ClientBuilder.MAX_ACTIONS_PER_REQUEST, MAX_ACTIONS)
                .put(ClientBuilder.FLUSH_INTERVAL, TimeValue.timeValueSeconds(60))
                .setMetric(new SimpleBulkMetric())
                .setControl(new SimpleBulkControl())
                .toBulkTransportClient();
        try {
            client.newIndex("test");
            for (int i = 0; i < NUM_ACTIONS; i++) {
                client.index("test", "test", null, "{ \"name\" : \"" + randomString(32) + "\"}");
            }
            client.flushIngest();
            client.waitForResponses(TimeValue.timeValueSeconds(30));
        } catch (InterruptedException e) {
            // ignore
        } catch (ExecutionException e) {
            logger.error(e.getMessage(), e);
        } catch (NoNodeAvailableException e) {
            logger.warn("skipping, no node available");
        } catch (Throwable t) {
            logger.error("unexcepted: " + t.getMessage(), t);
        } finally {
            logger.info("assuring {} == {}", numactions, client.getMetric().getSucceeded().getCount());
            assertEquals(numactions, client.getMetric().getSucceeded().getCount());
            if (client.hasThrowable()) {
                logger.error("error", client.getThrowable());
            }
            assertFalse(client.hasThrowable());
            client.shutdown();
        }
    }

    @Test
    public void testThreadedRandomDocsBulkClient() {
        int maxthreads = Runtime.getRuntime().availableProcessors();
        long maxactions = MAX_ACTIONS;
        final long maxloop = NUM_ACTIONS;
        logger.info("firing up client");
        final BulkTransportClient client = ClientBuilder.builder()
                .put(getClientSettings())
                .put(ClientBuilder.MAX_ACTIONS_PER_REQUEST, maxactions)
                .put(ClientBuilder.FLUSH_INTERVAL, TimeValue.timeValueSeconds(60)) // = disable autoflush for this test
                .setMetric(new SimpleBulkMetric())
                .setControl(new SimpleBulkControl())
                .toBulkTransportClient();
        try {
            logger.info("new index");
            Settings settingsForIndex = Settings.builder()
                    .put("index.number_of_shards", 2)
                    .put("index.number_of_replicas", 1)
                    .build();
            client.newIndex("test", settingsForIndex, null)
                    .startBulk("test", -1, 1000);
            logger.info("pool");
            ExecutorService executorService = Executors.newFixedThreadPool(maxthreads);
            final CountDownLatch latch = new CountDownLatch(maxthreads);
            for (int i = 0; i < maxthreads; i++) {
                executorService.execute(() -> {
                    logger.info("executing runnable");
                    for (int i1 = 0; i1 < maxloop; i1++) {
                        client.index("test", "test", null, "{ \"name\" : \"" + randomString(32) + "\"}");
                    }
                    logger.info("done runnable");
                    latch.countDown();
                });
            }
            logger.info("waiting for max 30 seconds...");
            latch.await(30, TimeUnit.SECONDS);
            logger.info("client flush ...");
            client.flushIngest();
            client.waitForResponses(TimeValue.timeValueSeconds(30));
            logger.info("executor service to be shut down ...");
            executorService.shutdown();
            logger.info("executor service is shut down");
            client.stopBulk("test");
        } catch (NoNodeAvailableException e) {
            logger.warn("skipping, no node available");
        } catch (Throwable t) {
            logger.error("unexpected error: " + t.getMessage(), t);
        } finally {
            logger.info("assuring {} == {}", maxthreads * maxloop, client.getMetric().getSucceeded().getCount());
            assertEquals(maxthreads * maxloop, client.getMetric().getSucceeded().getCount());
            if (client.hasThrowable()) {
                logger.error("error", client.getThrowable());
            }
            assertFalse(client.hasThrowable());
            client.refreshIndex("test");
            SearchRequestBuilder searchRequestBuilder = new SearchRequestBuilder(client.client(), SearchAction.INSTANCE)
                    .setIndices("test")
                    .setQuery(QueryBuilders.matchAllQuery())
                    .setSize(0);
            assertEquals(maxthreads * maxloop,
                    searchRequestBuilder.execute().actionGet().getHits().getTotalHits());
            client.shutdown();
        }
    }
}
