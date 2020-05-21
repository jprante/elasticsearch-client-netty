package org.xbib.elx.node;

import org.xbib.elx.api.AdminClientProvider;

public class NodeAdminClientProvider implements AdminClientProvider<NodeAdminClient> {

    @Override
    public NodeAdminClient getClient() {
        return new NodeAdminClient();
    }
}
