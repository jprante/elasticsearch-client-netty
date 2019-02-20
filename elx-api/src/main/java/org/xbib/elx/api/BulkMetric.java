package org.xbib.elx.api;

import org.elasticsearch.common.settings.Settings;
import org.xbib.metrics.Count;
import org.xbib.metrics.Metered;

import java.io.Closeable;

public interface BulkMetric extends Closeable {

    void init(Settings settings);

    Metered getTotalIngest();

    Count getTotalIngestSizeInBytes();

    Count getCurrentIngest();

    Count getCurrentIngestNumDocs();

    Count getSubmitted();

    Count getSucceeded();

    Count getFailed();

    long elapsed();

    void start();

    void stop();
}
