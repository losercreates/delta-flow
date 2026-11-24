package io.deltaflow.security.api;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.security.domain.SecurityPrincipal;
import io.deltaflow.security.dto.AuthLoginRequest;
import io.deltaflow.security.dto.AuthLoginResponse;
import io.deltaflow.security.dto.CurrentUserProfileDto;
import io.deltaflow.security.dto.TenantRateLimitStatusDto;
import io.deltaflow.security.service.JwtTokenService;
import io.deltaflow.security.service.TenantRateLimiterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

    private final JwtTokenService jwtTokenService;
    private final TenantRateLimiterService rateLimiterService;
    private final AuditLogger auditLogger;

    public AuthApiController(
            JwtTokenService jwtTokenService,
            TenantRateLimiterService rateLimiterService,
            AuditLogger auditLogger
    ) {
        this.jwtTokenService = jwtTokenService;
        this.rateLimiterService = rateLimiterService;
        this.auditLogger = auditLogger;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        String username = request.username().trim();
        String password = request.password().trim();
        String tenantId = (request.tenantId() != null && !request.tenantId().isBlank()) 
                ? request.tenantId().trim() 
                : "default";

        Set<String> roles = new HashSet<>();

        // Standard operator credentials mapping
        if ("admin".equalsIgnoreCase(username) && ("password123".equals(password) || "admin123".equals(password))) {
            roles.add("ROLE_ADMIN");
            roles.add("ROLE_OPERATOR");
            roles.add("ROLE_VIEWER");
        } else if ("operator".equalsIgnoreCase(username) && "password123".equals(password)) {
            roles.add("ROLE_OPERATOR");
            roles.add("ROLE_VIEWER");
        } else if ("viewer".equalsIgnoreCase(username) && "password123".equals(password)) {
            roles.add("ROLE_VIEWER");
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = jwtTokenService.generateToken(username, tenantId, roles);

        Map<String, Object> details = new HashMap<>();
        details.put("username", username);
        details.put("tenantId", tenantId);
        details.put("roles", roles);
        auditLogger.logEvent("OPERATOR_LOGIN", username, "auth", details);

        return ResponseEntity.ok(new AuthLoginResponse(
                token,
                "Bearer",
                86400,
                tenantId,
                roles
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserProfileDto> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SecurityPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(new CurrentUserProfileDto(
                principal.principalId(),
                principal.tenantId(),
                principal.roles(),
                principal.authType().name()
        ));
    }

    @GetMapping("/rate-limit/status")
    public ResponseEntity<TenantRateLimitStatusDto> getRateLimitStatus(
            @RequestParam(name = "tenantId", required = false) String paramTenantId
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String tenantId = paramTenantId;
        if ((tenantId == null || tenantId.isBlank()) && auth != null && auth.getPrincipal() instanceof SecurityPrincipal principal) {
            tenantId = principal.tenantId();
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        TenantRateLimiterService.RateLimitResult res = rateLimiterService.tryConsume(tenantId, 0);

        return ResponseEntity.ok(new TenantRateLimitStatusDto(
                tenantId,
                res.limit(),
                res.remaining(),
                res.resetMs(),
                !res.allowed()
        ));
    }
}
