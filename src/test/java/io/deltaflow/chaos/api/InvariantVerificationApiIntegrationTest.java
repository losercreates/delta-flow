package io.deltaflow.chaos.api;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.chaos.domain.InvariantVerificationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

public class InvariantVerificationApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("US3: GET /api/v1/invariants/verify executes balance audit and returns report")
    void testVerifyFinancialInvariants() {
        ResponseEntity<InvariantVerificationReport> response = restTemplate.getForEntity(
                "/api/v1/invariants/verify",
                InvariantVerificationReport.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        InvariantVerificationReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.doubleSpendCount()).isEqualTo(0);
        assertThat(report.orphanedDebitCount()).isEqualTo(0);
        assertThat(report.status()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("US3: GET /api/v1/invariants/verify?tenantId=bank-alpha filters by tenant")
    void testVerifyFinancialInvariantsWithTenant() {
        ResponseEntity<InvariantVerificationReport> response = restTemplate.getForEntity(
                "/api/v1/invariants/verify?tenantId=bank-alpha",
                InvariantVerificationReport.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        InvariantVerificationReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo("PASSED");
    }
}
