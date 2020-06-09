package org.xbib.elx.http.action.admin.indices.delete;

import org.elasticsearch.action.admin.indices.delete.DeleteIndexAction;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.common.xcontent.XContentParser;
import org.xbib.elx.http.HttpAction;
import org.xbib.netty.http.client.api.Request;
import org.xbib.netty.http.common.HttpResponse;

import java.io.IOException;

public class HttpDeleteIndexAction extends HttpAction<DeleteIndexRequest, DeleteIndexResponse> {

    @Override
    public DeleteIndexAction getActionInstance() {
        return DeleteIndexAction.INSTANCE;
    }

    @Override
    protected Request.Builder createHttpRequest(String url, DeleteIndexRequest deleteIndexRequest) {
        return newDeleteRequest(url, "/" + String.join(",", deleteIndexRequest.indices()));
    }

    @Override
    protected CheckedFunction<XContentParser, DeleteIndexResponse, IOException> entityParser(HttpResponse httpResponse) {
        return DeleteIndexResponse::fromXContent;
    }
}
