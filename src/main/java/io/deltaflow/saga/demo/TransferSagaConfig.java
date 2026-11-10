package io.deltaflow.saga.demo;

import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.demo.service.CreditService;
import io.deltaflow.saga.demo.service.DebitService;
import io.deltaflow.saga.demo.service.LedgerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
public class TransferSagaConfig {

    public static final String SAGA_NAME = "account-transfer";

    @Bean
    public SagaDefinition<TransferRequestDto, TransferResponseDto> transferSagaDefinition(
            DebitService debitService,
            CreditService creditService,
            LedgerService ledgerService
    ) {
        return SagaBuilder.<TransferRequestDto, TransferResponseDto>named(SAGA_NAME)
                .step("debit-step")
                    .action((ctx, req) -> debitService.debit(req.sourceAccountId(), req.amount(), ctx.getIdempotencyKey()))
                    .compensate((ctx, req) -> debitService.compensateDebit(req.sourceAccountId(), req.amount(), ctx.getIdempotencyKey()))
                .step("credit-step")
                    .action((ctx, req) -> creditService.credit(req.targetAccountId(), req.amount(), ctx.getIdempotencyKey()))
                    .compensate((ctx, req) -> creditService.compensateCredit(req.targetAccountId(), req.amount(), ctx.getIdempotencyKey()))
                .step("ledger-step")
                    .action((ctx, req) -> {
                        DebitService.DebitResult debitResult = ctx.getStepResult("debit-step", DebitService.DebitResult.class);
                        CreditService.CreditResult creditResult = ctx.getStepResult("credit-step", CreditService.CreditResult.class);
                        String debitRef = debitResult != null ? debitResult.debitId() : "N/A";
                        String creditRef = creditResult != null ? creditResult.creditId() : "N/A";
                        return ledgerService.recordEntry(debitRef, creditRef, req.amount(), req.currency());
                    })
                .onCompletion((ctx, req) -> new TransferResponseDto(
                        ctx.getSagaId(),
                        "tx_" + ctx.getIdempotencyKey(),
                        "COMPLETED",
                        req.amount(),
                        req.currency(),
                        3,
                        Instant.now()
                ))
                .build();
    }
}

