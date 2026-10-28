package com.liverpool.ms_clp_blp.shared.logging;

import java.util.function.Supplier;

import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * A {@link LoggingEventBuilder} that drops key-value pairs whose value is {@code null}.
 *
 * <p>A field is either known or absent — {@code errorClass="null" detail="null"} is noise that reads
 * like data. The JSON formatter already skipped nulls, but Logback's {@code %kvp} converter prints
 * whatever is attached, so the local console showed them. Filtering here rather than at each call
 * site means a field that is only sometimes available (an upstream's response body, a Jewel model, a
 * cause chain) can be attached unconditionally and simply not appear when there is nothing to say.</p>
 *
 * <p>Every method delegates; the class exists solely for the two {@code addKeyValue} overloads.
 * {@link LogEvents#at} returns the underlying NOP builder untouched when the level is disabled, so
 * this wrapper is never allocated for a line that will not be emitted.</p>
 */
final class NullSafeEventBuilder implements LoggingEventBuilder {

    private final LoggingEventBuilder delegate;

    NullSafeEventBuilder(LoggingEventBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public LoggingEventBuilder addKeyValue(String key, Object value) {
        return value == null ? this : wrap(delegate.addKeyValue(key, value));
    }

    @Override
    public LoggingEventBuilder addKeyValue(String key, Supplier<Object> valueSupplier) {
        if (valueSupplier == null) {
            return this;
        }
        // Resolved here rather than deferred: the value decides whether the pair exists at all, and
        // the builder is only ever created when the level is already known to be enabled.
        return addKeyValue(key, valueSupplier.get());
    }

    @Override
    public LoggingEventBuilder setCause(Throwable cause) {
        return wrap(delegate.setCause(cause));
    }

    @Override
    public LoggingEventBuilder addMarker(Marker marker) {
        return wrap(delegate.addMarker(marker));
    }

    @Override
    public LoggingEventBuilder addArgument(Object p) {
        return wrap(delegate.addArgument(p));
    }

    @Override
    public LoggingEventBuilder addArgument(Supplier<?> objectSupplier) {
        return wrap(delegate.addArgument(objectSupplier));
    }

    @Override
    public LoggingEventBuilder setMessage(String message) {
        return wrap(delegate.setMessage(message));
    }

    @Override
    public LoggingEventBuilder setMessage(Supplier<String> messageSupplier) {
        return wrap(delegate.setMessage(messageSupplier));
    }

    @Override
    public void log() {
        delegate.log();
    }

    @Override
    public void log(String message) {
        delegate.log(message);
    }

    @Override
    public void log(Supplier<String> messageSupplier) {
        delegate.log(messageSupplier);
    }

    @Override
    public void log(String message, Object arg) {
        delegate.log(message, arg);
    }

    @Override
    public void log(String message, Object arg0, Object arg1) {
        delegate.log(message, arg0, arg1);
    }

    @Override
    public void log(String message, Object... args) {
        delegate.log(message, args);
    }

    /** Keeps the null-filtering in place across a chain, whatever the delegate returns. */
    private LoggingEventBuilder wrap(LoggingEventBuilder returned) {
        return returned == delegate ? this : new NullSafeEventBuilder(returned);
    }
}
