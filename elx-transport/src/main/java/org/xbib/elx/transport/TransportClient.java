package org.xbib.elx.transport;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

import org.elasticsearch.Version;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.GenericAction;
import org.elasticsearch.action.TransportActionNodeProxy;
import org.elasticsearch.action.admin.cluster.node.liveness.LivenessRequest;
import org.elasticsearch.action.admin.cluster.node.liveness.LivenessResponse;
import org.elasticsearch.action.admin.cluster.node.liveness.TransportLivenessAction;
import org.elasticsearch.cache.recycler.PageCacheRecycler;
import org.elasticsearch.client.support.AbstractClient;
import org.elasticsearch.client.support.Headers;
import org.elasticsearch.client.transport.ClientTransportModule;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterNameModule;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.indices.breaker.CircuitBreakerModule;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsModule;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.elasticsearch.transport.FutureTransportResponseHandler;
import org.elasticsearch.transport.TransportModule;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stripped-down transport client without node sampling and without retrying.
 *
 * Merged together: original TransportClient, TransportClientNodesServce, TransportClientProxy

 * Configurable connect ping interval setting added.
 */
public class TransportClient extends AbstractClient {

    private static final String CLIENT_TYPE = "transport";

    private final Injector injector;

    private final ProxyActionMap proxyActionMap;

    private final long pingTimeout;

    private final ClusterName clusterName;

    private final TransportService transportService;

    private final Version minCompatibilityVersion;

    private final Headers headers;

    private final AtomicInteger tempNodeId = new AtomicInteger();

    private final AtomicInteger nodeCounter = new AtomicInteger();

    private final Object mutex = new Object();

    private volatile List<DiscoveryNode> listedNodes = Collections.emptyList();

    private volatile List<DiscoveryNode> nodes = Collections.emptyList();

    private volatile List<DiscoveryNode> filteredNodes = Collections.emptyList();

    private volatile boolean closed;

    private TransportClient(Injector injector) {
        super(injector.getInstance(Settings.class), injector.getInstance(ThreadPool.class),
                injector.getInstance(Headers.class));
        this.injector = injector;
        this.clusterName = injector.getInstance(ClusterName.class);
        this.transportService = injector.getInstance(TransportService.class);
        this.minCompatibilityVersion = injector.getInstance(Version.class).minimumCompatibilityVersion();
        this.headers = injector.getInstance(Headers.class);
        this.pingTimeout = this.settings.getAsTime("client.transport.ping_timeout", timeValueSeconds(5)).millis();
        this.proxyActionMap = injector.getInstance(ProxyActionMap.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the current registered transport addresses to use.
     *
     * @return list of transport addresess
     */
    public List<TransportAddress> transportAddresses() {
        List<TransportAddress> lstBuilder = new ArrayList<>();
        for (DiscoveryNode listedNode : listedNodes) {
            lstBuilder.add(listedNode.address());
        }
        return Collections.unmodifiableList(lstBuilder);
    }

    /**
     * Returns the current connected transport nodes that this client will use.
     * The nodes include all the nodes that are currently alive based on the transport
     * addresses provided.
     *
     * @return list of nodes
     */
    public List<DiscoveryNode> connectedNodes() {
        return this.nodes;
    }

    /**
     * The list of filtered nodes that were not connected to, for example, due to
     * mismatch in cluster name.
     *
     * @return list of nodes
     */
    public List<DiscoveryNode> filteredNodes() {
        return this.filteredNodes;
    }

    /**
     * Returns the listed nodes in the transport client (ones added to it).
     *
     * @return list of nodes
     */
    public List<DiscoveryNode> listedNodes() {
        return this.listedNodes;
    }

    /**
     * Adds a list of transport addresses that will be used to connect to.
     * The Node this transport address represents will be used if its possible to connect to it.
     * If it is unavailable, it will be automatically connected to once it is up.
     * In order to get the list of all the current connected nodes, please see {@link #connectedNodes()}.
     *
     * @param discoveryNodes nodes
     * @return this transport client
     */
    public TransportClient addDiscoveryNodes(DiscoveryNodes discoveryNodes) {
        Collection<InetSocketTransportAddress> addresses = new ArrayList<>();
        for (DiscoveryNode discoveryNode : discoveryNodes) {
            addresses.add((InetSocketTransportAddress) discoveryNode.address());
        }
        addTransportAddresses(addresses);
        return this;
    }

    public TransportClient addTransportAddresses(Collection<InetSocketTransportAddress> transportAddresses) {
        synchronized (mutex) {
            if (closed) {
                throw new IllegalStateException("transport client is closed, can't add addresses");
            }
            List<TransportAddress> filtered = new ArrayList<>(transportAddresses.size());
            for (TransportAddress transportAddress : transportAddresses) {
                boolean found = false;
                for (DiscoveryNode otherNode : listedNodes) {
                    if (otherNode.address().equals(transportAddress)) {
                        found = true;
                        logger.debug("address [{}] already exists with [{}], ignoring...", transportAddress, otherNode);
                        break;
                    }
                }
                if (!found) {
                    filtered.add(transportAddress);
                }
            }
            if (filtered.isEmpty()) {
                return this;
            }
            List<DiscoveryNode> discoveryNodeList = new ArrayList<>();
            discoveryNodeList.addAll(listedNodes());
            for (TransportAddress transportAddress : filtered) {
                DiscoveryNode node = new DiscoveryNode("#transport#-" + tempNodeId.incrementAndGet(), transportAddress,
                        minCompatibilityVersion);
                logger.debug("adding address [{}]", node);
                discoveryNodeList.add(node);
            }
            listedNodes = Collections.unmodifiableList(discoveryNodeList);
            connect();
        }
        return this;
    }

    /**
     * Removes a transport address from the list of transport addresses that are used to connect to.
     *
     * @param transportAddress transport address to remove
     * @return this transport client
     */
    public TransportClient removeTransportAddress(TransportAddress transportAddress) {
        synchronized (mutex) {
            if (closed) {
                throw new IllegalStateException("transport client is closed, can't remove an address");
            }
            List<DiscoveryNode> builder = new ArrayList<>();
            for (DiscoveryNode otherNode : listedNodes) {
                if (!otherNode.address().equals(transportAddress)) {
                    builder.add(otherNode);
                } else {
                    logger.debug("removing address [{}]", otherNode);
                }
            }
            listedNodes = Collections.unmodifiableList(builder);
        }
        return this;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void close() {
        synchronized (mutex) {
            if (closed) {
                return;
            }
            closed = true;
            for (DiscoveryNode node : nodes) {
                transportService.disconnectFromNode(node);
            }
            for (DiscoveryNode listedNode : listedNodes) {
                transportService.disconnectFromNode(listedNode);
            }
            nodes = Collections.emptyList();
        }
        injector.getInstance(TransportService.class).close();
        for (Class<? extends LifecycleComponent> plugin : injector.getInstance(PluginsService.class).nodeServices()) {
            injector.getInstance(plugin).close();
        }
        try {
            ThreadPool.terminate(injector.getInstance(ThreadPool.class), 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
        }
        injector.getInstance(PageCacheRecycler.class).close();
    }

    private void connect() {
        Set<DiscoveryNode> newNodes = new HashSet<>();
        Set<DiscoveryNode> newFilteredNodes = new HashSet<>();
        for (DiscoveryNode listedNode : listedNodes) {
            if (!transportService.nodeConnected(listedNode)) {
                try {
                    logger.trace("connecting to listed node (light) [{}]", listedNode);
                    transportService.connectToNodeLight(listedNode);
                } catch (Exception e) {
                    logger.debug("failed to connect to node [{}], removed from nodes list", e, listedNode);
                    continue;
                }
            }
            try {
                FutureTransportResponseHandler<LivenessResponse> responseHandler = new LivenessResponseHandler();
                LivenessResponse livenessResponse = transportService.submitRequest(listedNode,
                        TransportLivenessAction.NAME, headers.applyTo(new LivenessRequest()),
                        TransportRequestOptions.builder().withType(TransportRequestOptions.Type.STATE)
                                .withTimeout(pingTimeout).build(),responseHandler).txGet();
                if (!clusterName.equals(livenessResponse.getClusterName())) {
                    logger.warn("node {} not part of the cluster {}, ignoring...", listedNode, clusterName);
                    newFilteredNodes.add(listedNode);
                } else if (livenessResponse.getDiscoveryNode() != null) {
                    DiscoveryNode nodeWithInfo = livenessResponse.getDiscoveryNode();
                    newNodes.add(new DiscoveryNode(nodeWithInfo.name(), nodeWithInfo.id(), nodeWithInfo.getHostName(),
                            nodeWithInfo.getHostAddress(), listedNode.address(), nodeWithInfo.attributes(),
                            nodeWithInfo.version()));
                } else {
                    logger.debug("node {} didn't return any discovery info, temporarily using transport discovery node",
                            listedNode);
                    newNodes.add(listedNode);
                }
            } catch (Exception e) {
                logger.info("failed to get node info for {}, disconnecting...", e, listedNode);
                transportService.disconnectFromNode(listedNode);
            }
        }
        for (Iterator<DiscoveryNode> it = newNodes.iterator(); it.hasNext(); ) {
            DiscoveryNode node = it.next();
            if (!transportService.nodeConnected(node)) {
                try {
                    logger.trace("connecting to node [{}]", node);
                    transportService.connectToNode(node);
                } catch (Exception e) {
                    it.remove();
                    logger.debug("failed to connect to discovered node [" + node + "]", e);
                }
            }
        }
        this.nodes = Collections.unmodifiableList(new ArrayList<>(newNodes));
        this.filteredNodes = Collections.unmodifiableList(new ArrayList<>(newFilteredNodes));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected <R extends ActionRequest, S extends ActionResponse, T extends ActionRequestBuilder<R, S, T>>
    void doExecute(Action<R, S, T> action, final R request, final ActionListener<S> listener) {
        final TransportActionNodeProxy<R, S> proxyAction = proxyActionMap.getProxies().get(action);
        if (proxyAction == null) {
            throw new IllegalStateException("undefined action " + action);
        }
        List<DiscoveryNode> nodeList = this.nodes;
        if (nodeList.isEmpty()) {
            throw new NoNodeAvailableException("none of the configured nodes are available: " + this.listedNodes);
        }
        int index = nodeCounter.incrementAndGet();
        if (index < 0) {
            index = 0;
            nodeCounter.set(0);
        }
        // try once and never more
        try {
            proxyAction.execute(nodeList.get(index % nodeList.size()), request, listener);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    public static class Builder {

        private Settings settings = Settings.EMPTY;

        private List<Class<? extends Plugin>> pluginClasses = new ArrayList<>();

        public Builder settings(Settings.Builder settings) {
            return settings(settings.build());
        }

        public Builder settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public Builder addPlugin(Class<? extends Plugin> pluginClass) {
            pluginClasses.add(pluginClass);
            return this;
        }

        public TransportClient build() {
            Settings transportClientSettings = settingsBuilder()
                    .put("transport.ping.schedule", this.settings.get("ping.interval", "30s"))
                    .put(InternalSettingsPreparer.prepareSettings(this.settings))
                    .put("network.server", false)
                    .put("node.client", true)
                    .put(CLIENT_TYPE_SETTING, CLIENT_TYPE)
                    .build();
            PluginsService pluginsService = new PluginsService(transportClientSettings, null, null, pluginClasses);
            this.settings = pluginsService.updatedSettings();
            Version version = Version.CURRENT;
            final ThreadPool threadPool = new ThreadPool(transportClientSettings);
            boolean success = false;
            try {
                ModulesBuilder modules = new ModulesBuilder();
                modules.add(new Version.Module(version));
                // plugin modules must be added here, before others or we can get crazy injection errors...
                for (Module pluginModule : pluginsService.nodeModules()) {
                    modules.add(pluginModule);
                }
                modules.add(new PluginsModule(pluginsService));
                modules.add(new SettingsModule(this.settings));
                modules.add(new NetworkModule());
                modules.add(new ClusterNameModule(this.settings));
                modules.add(new ThreadPoolModule(threadPool));
                modules.add(new TransportModule(this.settings));
                modules.add(new TransportSearchModule());
                modules.add(new ActionModule(true));
                modules.add(new ClientTransportModule());
                modules.add(new CircuitBreakerModule(this.settings));
                pluginsService.processModules(modules);
                Injector injector = modules.createInjector();
                injector.getInstance(TransportService.class).start();
                TransportClient transportClient = new TransportClient(injector);
                success = true;
                return transportClient;
            } finally {
                if (!success) {
                    ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
                }
            }
        }
    }

    /**
     * The {@link ProxyActionMap} must be declared public for injection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static class ProxyActionMap {

        private final Map<Action, TransportActionNodeProxy> proxies;

        @Inject
        public ProxyActionMap(Settings settings, TransportService transportService, Map<String, GenericAction> actions) {
            this.proxies = new LinkedHashMap<>();
            for (GenericAction action : actions.values()) {
                if (action instanceof Action) {
                    this.proxies.put((Action) action, new TransportActionNodeProxy(settings, action, transportService));
                }
            }
        }

        public Map<Action, TransportActionNodeProxy> getProxies() {
            return proxies;
        }
    }

    private static class LivenessResponseHandler extends FutureTransportResponseHandler<LivenessResponse> {
        @Override
        public LivenessResponse newInstance() {
            return new LivenessResponse();
        }
    }

    private static class TransportSearchModule extends SearchModule {
        @Override
        protected void configure() {
            // noop
        }
    }
}
