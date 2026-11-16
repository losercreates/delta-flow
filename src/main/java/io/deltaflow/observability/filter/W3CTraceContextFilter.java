package io.deltaflow.observability.filter;

import io.deltaflow.observability.tracing.TraceContextHolder;
import io.deltaflow.observability.tracing.TraceContextMetadata;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class W3CTraceContextFilter implements Filter {

    public static final String TRACEPARENT_HEADER = "traceparent";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            String incomingTraceparent = httpRequest.getHeader(TRACEPARENT_HEADER);
            TraceContextMetadata metadata = TraceContextMetadata.fromTraceparent(incomingTraceparent);
            TraceContextHolder.set(metadata);

            httpResponse.setHeader(TRACEPARENT_HEADER, metadata.traceparent());

            try {
                chain.doFilter(request, response);
            } finally {
                TraceContextHolder.clear();
            }
        } else {
            chain.doFilter(request, response);
        }
    }
}
