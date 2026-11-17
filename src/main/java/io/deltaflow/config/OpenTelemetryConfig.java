package io.deltaflow.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4318/v1/traces}")
    private String otlpEndpoint;

    @Value("${otel.exporter.otlp.enabled:true}")
    private boolean otlpEnabled;

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry() {
        Resource serviceResource = Resource.getDefault().merge(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "delta-flow"))
        );

        SdkTracerProvider tracerProvider;
        if (otlpEnabled && otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            log.info("Configuring OpenTelemetry OTLP HTTP span exporter targeting: {}", otlpEndpoint);
            try {
                OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                        .setEndpoint(otlpEndpoint)
                        .setTimeout(Duration.ofSeconds(3))
                        .build();

                tracerProvider = SdkTracerProvider.builder()
                        .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                                .setScheduleDelay(Duration.ofMillis(500))
                                .setMaxExportBatchSize(512)
                                .build())
                        .setResource(serviceResource)
                        .build();
            } catch (Exception ex) {
                log.warn("Failed to initialize OTLP exporter, falling back to default SDK tracer: {}", ex.getMessage());
                tracerProvider = SdkTracerProvider.builder().setResource(serviceResource).build();
            }
        } else {
            log.info("OTLP exporter disabled, using no-op SDK tracer provider");
            tracerProvider = SdkTracerProvider.builder().setResource(serviceResource).build();
        }

        OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        try {
            GlobalOpenTelemetry.resetForTest();
            GlobalOpenTelemetry.set(openTelemetrySdk);
        } catch (Throwable t) {
            log.debug("GlobalOpenTelemetry registration note: {}", t.getMessage());
        }

        return openTelemetrySdk;
    }

    @Bean
    public Tracer deltaFlowTracerBean(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("io.deltaflow.saga");
    }
}
