package io.deltaflow.observability.tracing;

public final class TraceContextHolder {

    private static final ThreadLocal<TraceContextMetadata> CURRENT_CONTEXT = new InheritableThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void set(TraceContextMetadata metadata) {
        if (metadata != null) {
            CURRENT_CONTEXT.set(metadata);
        } else {
            CURRENT_CONTEXT.remove();
        }
    }

    public static TraceContextMetadata get() {
        return CURRENT_CONTEXT.get();
    }

    public static String getTraceId() {
        TraceContextMetadata current = get();
        return current != null ? current.traceId() : null;
    }

    public static String getTraceparent() {
        TraceContextMetadata current = get();
        return current != null ? current.traceparent() : null;
    }

    public static void clear() {
        CURRENT_CONTEXT.remove();
    }
}
