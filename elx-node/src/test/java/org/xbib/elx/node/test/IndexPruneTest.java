package org.xbib.elx.node.test;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.xbib.elx.api.IndexPruneResult;
import org.xbib.elx.common.ClientBuilder;
import org.xbib.elx.node.NodeAdminClient;
import org.xbib.elx.node.NodeAdminClientProvider;
import org.xbib.elx.node.NodeBulkClient;
import org.xbib.elx.node.NodeBulkClientProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestExtension.class)
class IndexPruneTest {

    private static final Logger logger = LogManager.getLogger(IndexShiftTest.class.getName());

    private final TestExtension.Helper helper;

    IndexPruneTest(TestExtension.Helper helper) {
        this.helper = helper;
    }

    @Test
    void testPrune() throws IOException {
        try (NodeAdminClient adminClient = ClientBuilder.builder(helper.client)
                .setAdminClientProvider(NodeAdminClientProvider.class)
                .put(helper.getNodeSettings())
                .build();
             NodeBulkClient bulkClient = ClientBuilder.builder(helper.client)
                .setBulkClientProvider(NodeBulkClientProvider.class)
                     .put(helper.getNodeSettings())
                .build()) {
            Settings settings = Settings.builder()
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 0)
                    .build();
            bulkClient.newIndex("test_prune1", settings);
            adminClient.shiftIndex("test_prune", "test_prune1", Collections.emptyList());
            bulkClient.newIndex("test_prune2", settings);
            adminClient.shiftIndex("test_prune", "test_prune2", Collections.emptyList());
            bulkClient.newIndex("test_prune3", settings);
            adminClient.shiftIndex("test_prune", "test_prune3", Collections.emptyList());
            bulkClient.newIndex("test_prune4", settings);
            adminClient.shiftIndex("test_prune", "test_prune4", Collections.emptyList());
            IndexPruneResult indexPruneResult =
                    adminClient.pruneIndex("test_prune", "test_prune4", 2, 2, true);
            assertTrue(indexPruneResult.getDeletedIndices().contains("test_prune1"));
            assertTrue(indexPruneResult.getDeletedIndices().contains("test_prune2"));
            assertFalse(indexPruneResult.getDeletedIndices().contains("test_prune3"));
            assertFalse(indexPruneResult.getDeletedIndices().contains("test_prune4"));
            List<Boolean> list = new ArrayList<>();
            for (String index : Arrays.asList("test_prune1", "test_prune2", "test_prune3", "test_prune4")) {
                list.add(adminClient.isIndexExists(index));
            }
            logger.info(list);
            assertFalse(list.get(0));
            assertFalse(list.get(1));
            assertTrue(list.get(2));
            assertTrue(list.get(3));
            if (bulkClient.getBulkController().getLastBulkError() != null) {
                logger.error("error", bulkClient.getBulkController().getLastBulkError());
            }
            assertNull(bulkClient.getBulkController().getLastBulkError());
        }
    }
}
