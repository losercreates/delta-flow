package io.deltaflow.security.filter;

import io.deltaflow.security.domain.SecurityPrincipal;
import io.deltaflow.security.service.ApiKeyAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyAuthenticationService apiKeyAuthenticationService;

    public ApiKeyAuthenticationFilter(ApiKeyAuthenticationService apiKeyAuthenticationService) {
        this.apiKeyAuthenticationService = apiKeyAuthenticationService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<SecurityPrincipal> principalOpt = apiKeyAuthenticationService.authenticateApiKey(apiKey);
            if (principalOpt.isPresent()) {
                SecurityPrincipal principal = principalOpt.get();
                var authorities = principal.roles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        authorities
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute("tenantId", principal.tenantId());
            }
        }

        filterChain.doFilter(request, response);
    }
}
