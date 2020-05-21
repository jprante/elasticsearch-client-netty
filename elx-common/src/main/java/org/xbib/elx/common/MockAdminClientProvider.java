package org.xbib.elx.common;

import org.xbib.elx.api.AdminClientProvider;

public class MockAdminClientProvider implements AdminClientProvider<MockAdminClient> {
    @Override
    public MockAdminClient getClient() {
        return new MockAdminClient();
    }
}
