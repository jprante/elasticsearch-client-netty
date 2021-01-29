package org.xbib.elx.api;

import java.util.concurrent.TimeUnit;

public interface IndexDefinition {

    /**
     * The one and only index type name used in the extended client.
     * Note that all Elasticsearch version &lt; 6.2.0 do not allow a prepending "_".
     */
    String TYPE_NAME = "_doc";

    IndexDefinition setIndex(String index);

    String getIndex();

    IndexDefinition setFullIndexName(String fullIndexName);

    String getFullIndexName();

    IndexDefinition setSettings(String settings);

    String getSettings();

    IndexDefinition setMappings(String mappings);

    String getMappings();

    IndexDefinition setDateTimePattern(String timeWindow);

    String getDateTimePattern();

    IndexDefinition setEnabled(boolean enabled);

    boolean isEnabled();

    IndexDefinition setIgnoreErrors(boolean ignoreErrors);

    boolean ignoreErrors();

    IndexDefinition setShift(boolean shift);

    boolean isShiftEnabled();

    IndexDefinition setForceMerge(boolean hasForceMerge);

    boolean hasForceMerge();

    IndexDefinition setReplicaLevel(int replicaLevel);

    int getReplicaLevel();

    IndexDefinition setRetention(IndexRetention indexRetention);

    IndexRetention getRetention();

    IndexDefinition setMaxWaitTime(long maxWaitTime, TimeUnit timeUnit);

    long getMaxWaitTime();

    TimeUnit getMaxWaitTimeUnit();

    IndexDefinition setStartRefreshInterval(long seconds);

    long getStartRefreshInterval();

    IndexDefinition setStopRefreshInterval(long seconds);

    long getStopRefreshInterval();
}
