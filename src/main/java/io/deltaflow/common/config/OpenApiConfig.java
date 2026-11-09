package io.deltaflow.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI deltaFlowOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("DeltaFlow Idempotency Proxy & Inspector API")
                .description("HTTP API contracts for DeltaFlow's high-consistency distributed idempotency gate.")
                .version("1.0.0"))
            .servers(List.of(new Server().url("http://localhost:8080").description("Local Development Server")));
    }
}

