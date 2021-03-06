package org.xbib.elx.http.util;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.XContentLocation;

import java.util.Optional;

/**
 * Thrown when one of the XContent parsers cannot parse something.
 */
public class XContentParseException extends IllegalArgumentException {

    private final Optional<XContentLocation> location;

    public XContentParseException(String message) {
        this(null, message);
    }

    public XContentParseException(XContentLocation location, String message) {
        super(message);
        this.location = Optional.ofNullable(location);
    }

    public XContentParseException(XContentLocation location, String message, Exception cause) {
        super(message, cause);
        this.location = Optional.ofNullable(location);
    }

    public int getLineNumber() {
        return location.map(l -> l.lineNumber).orElse(-1);
    }

    public int getColumnNumber() {
        return location.map(l -> l.columnNumber).orElse(-1);
    }

    @Nullable
    public XContentLocation getLocation() {
        return location.orElse(null);
    }

    @Override
    public String getMessage() {
        return location.map(l -> "[" + l.toString() + "] ").orElse("") + super.getMessage();
    }

}
