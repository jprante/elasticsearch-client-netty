package org.xbib.elx.node;

import io.netty.util.Version;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.netty4.Netty4Utils;
import org.xbib.elx.common.AbstractExtendedClient;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public class ExtendedNodeClient extends AbstractExtendedClient {

    private static final Logger logger = LogManager.getLogger(ExtendedNodeClient.class.getName());

    private Node node;

    @Override
    protected ElasticsearchClient createClient(Settings settings) throws IOException {
        if (settings == null) {
            return null;
        }
        String version = System.getProperty("os.name")
                + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.vm.vendor")
                + " " + System.getProperty("java.runtime.version")
                + " " + System.getProperty("java.vm.version");
        Settings effectiveSettings = Settings.builder().put(settings)
                .put("node.client", true)
                .put("node.master", false)
                .put("node.data", false)
                .build();
        XContentBuilder builder = XContentFactory.jsonBuilder();
        effectiveSettings.toXContent(builder, new ToXContent.MapParams(Collections.singletonMap("flat_settings", "true")));
        logger.info("creating node client on {} with effective settings {}",
                version, Strings.toString(builder));
        Collection<Class<? extends Plugin>> plugins = Collections.emptyList();
        this.node = new BulkNode(new Environment(effectiveSettings, null), plugins);
        try {
            node.start();
        } catch (Exception e) {
            throw new IOException(e);
        }
        return node.client();
    }

    @Override
    protected void closeClient() throws IOException {
        if (node != null) {
            logger.debug("closing node client");
            node.close();
        }
    }

    private static class BulkNode extends Node {

        BulkNode(Environment env, Collection<Class<? extends Plugin>> classpathPlugins) {
            super(env, classpathPlugins);
        }
    }
}
