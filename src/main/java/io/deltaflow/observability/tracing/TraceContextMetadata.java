package io.deltaflow.observability.tracing;

import java.security.SecureRandom;
import java.util.HexFormat;

public record TraceContextMetadata(
        String traceId,
        String spanId,
        String parentSpanId,
        String traceFlags,
        String traceparent
) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static TraceContextMetadata create(String traceId, String spanId, String parentSpanId, String traceFlags) {
        String flags = (traceFlags != null && !traceFlags.isBlank()) ? traceFlags : "01";
        String formatted = "00-" + traceId + "-" + spanId + "-" + flags;
        return new TraceContextMetadata(traceId, spanId, parentSpanId, flags, formatted);
    }

    public static TraceContextMetadata generate() {
        byte[] traceBytes = new byte[16];
        byte[] spanBytes = new byte[8];
        RANDOM.nextBytes(traceBytes);
        RANDOM.nextBytes(spanBytes);

        String traceId = HexFormat.of().formatHex(traceBytes);
        String spanId = HexFormat.of().formatHex(spanBytes);
        return create(traceId, spanId, null, "01");
    }

    public static TraceContextMetadata fromTraceparent(String header) {
        if (header == null || header.isBlank()) {
            return generate();
        }
        String[] parts = header.trim().split("-");
        if (parts.length != 4 || !"00".equals(parts[0]) || parts[1].length() != 32 || parts[2].length() != 16) {
            return generate();
        }
        String traceId = parts[1];
        String parentSpanId = parts[2];
        String traceFlags = parts[3];

        byte[] newSpanBytes = new byte[8];
        RANDOM.nextBytes(newSpanBytes);
        String childSpanId = HexFormat.of().formatHex(newSpanBytes);

        return create(traceId, childSpanId, parentSpanId, traceFlags);
    }
}
