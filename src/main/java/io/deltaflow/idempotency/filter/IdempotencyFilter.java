package io.deltaflow.idempotency.filter;

import io.deltaflow.common.exception.IdempotencyConflictException;
import io.deltaflow.common.exception.PayloadMismatchException;
import io.deltaflow.idempotency.domain.IdempotencyState;
import io.deltaflow.idempotency.domain.LockAcquisitionResult;
import io.deltaflow.idempotency.service.IdempotencyCoordinator;
import io.deltaflow.idempotency.service.PayloadHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyCoordinator coordinator;
    private final PayloadHasher payloadHasher;

    @Value("${deltaflow.idempotency.max-payload-size:2097152}")
    private int maxPayloadSize;

    public IdempotencyFilter(IdempotencyCoordinator coordinator, PayloadHasher payloadHasher) {
        this.coordinator = coordinator;
        this.payloadHasher = payloadHasher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Only apply to mutation API endpoints
        return !path.startsWith("/api/v1/payments") && !path.startsWith("/api/v1/transfers");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, 
        HttpServletResponse response, 
        FilterChain filterChain
    ) throws ServletException, IOException {

        String idempotencyKey = request.getHeader("Idempotency-Key");
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Missing required header
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"errorCode":"MISSING_IDEMPOTENCY_KEY","message":"The request is missing the required 'Idempotency-Key' header."}
                """);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, maxPayloadSize);
        CachedBodyHttpServletResponse cachedResponse = new CachedBodyHttpServletResponse(response);

        String requestHash = payloadHasher.computeHash(cachedRequest.getCachedBody(), request.getQueryString());

        LockAcquisitionResult result;
        try {
            result = coordinator.prepareOrResolve(tenantId, idempotencyKey, requestHash);
        } catch (PayloadMismatchException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(String.format(
                "{\"errorCode\":\"%s\",\"message\":\"%s\",\"idempotencyKey\":\"%s\"}",
                e.getErrorCode(), e.getMessage(), e.getIdempotencyKey()
            ));
            return;
        } catch (IdempotencyConflictException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(String.format(
                "{\"errorCode\":\"%s\",\"message\":\"%s\",\"idempotencyKey\":\"%s\"}",
                e.getErrorCode(), e.getMessage(), e.getIdempotencyKey()
            ));
            return;
        }

        // Java 21 Pattern Matching Switch
        switch (result) {
            case LockAcquisitionResult.Replayed(IdempotencyState.Resolved resolved) -> {
                response.setStatus(resolved.statusCode());
                if (resolved.headers() != null) {
                    for (Map.Entry<String, String> header : resolved.headers().entrySet()) {
                        response.setHeader(header.getKey(), header.getValue());
                    }
                }
                response.setHeader("Idempotency-Status", "CACHED");
                if (resolved.payload() != null && resolved.payload().length > 0) {
                    response.getOutputStream().write(resolved.payload());
                }
                response.flushBuffer();
            }
            case LockAcquisitionResult.Acquired acquired -> {
                response.setHeader("Idempotency-Status", "DIRECT");
                try {
                    filterChain.doFilter(cachedRequest, cachedResponse);
                    cachedResponse.copyBodyToResponse();

                    int status = cachedResponse.getStatus();
                    if (status >= 200 && status < 500) {
                        coordinator.saveResolvedResponse(
                            tenantId,
                            idempotencyKey,
                            status,
                            cachedResponse.getCapturedHeaders(),
                            cachedResponse.getCapturedBody()
                        );
                    } else {
                        coordinator.markFailed(tenantId, idempotencyKey, "Downstream returned status " + status);
                    }
                } catch (Exception ex) {
                    coordinator.markFailed(tenantId, idempotencyKey, ex.getMessage());
                    throw ex;
                }
            }
            case LockAcquisitionResult.PayloadMismatch mismatch -> {
                response.setStatus(HttpStatus.CONFLICT.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("""
                    {"errorCode":"IDEMPOTENCY_KEY_PAYLOAD_MISMATCH","message":"Payload SHA-256 digest does not match original request."}
                    """);
            }
            case LockAcquisitionResult.ConcurrentConflict conflict -> {
                response.setStatus(HttpStatus.CONFLICT.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("""
                    {"errorCode":"IDEMPOTENCY_KEY_IN_FLIGHT","message":"Transaction currently in progress."}
                    """);
            }
        }
    }
}

