package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SmsProofServiceTest extends SmsPostgresTestSupport {

    @Test
    void returnsThirtyTwoByteOpaqueTokenOnceAndStoresHashOnly() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());

        SmsProofService.IssuedProof proof = service.issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);

        assertThat(Base64.getUrlDecoder().decode(proof.token())).hasSize(32);
        assertThat(proof.toString()).doesNotContain(proof.token());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM platform.sms_proof WHERE id = ?",
                String.class, proof.id()))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(proof.token());
    }

    @Test
    void atomicallyConsumesOnceWithExactTenantUserAndPurposeOwnership() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());
        SmsProofService.IssuedProof proof = service.issue(
                tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);

        assertThatThrownBy(() -> service.consume(
                proof.token(), UUID.randomUUID(), userId, SmsPurpose.PAYMENT_CONFIRM))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consume(
                proof.token(), tenantId, UUID.randomUUID(), SmsPurpose.PAYMENT_CONFIRM))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consume(
                proof.token(), tenantId, userId, SmsPurpose.CHANGE_PASSWORD))
                .hasMessage("invalid_sms_proof");

        SmsProofService.ProofClaims claims = service.consume(
                proof.token(), tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.purpose()).isEqualTo(SmsPurpose.PAYMENT_CONFIRM);
        assertThatThrownBy(() -> service.consume(
                proof.token(), tenantId, userId, SmsPurpose.PAYMENT_CONFIRM))
                .hasMessage("invalid_sms_proof");
    }

    @Test
    void internalConsumeSucceedsRepeatedlyForSameProofAndOperation() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());
        SmsProofService.IssuedProof proof = service.issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);

        SmsProofService.ProofClaims first = service.consumeForOperation(
                proof.token(), tenantId, userId, SmsPurpose.API_KEY_RESET, operationId);
        SmsProofService.ProofClaims retry = service.consumeForOperation(
                proof.token(), tenantId, userId, SmsPurpose.API_KEY_RESET, operationId);

        assertThat(first).isEqualTo(retry);
        assertThat(first.userId()).isEqualTo(userId);
        assertThat(jdbc.queryForObject(
                "SELECT consumed_operation_id FROM platform.sms_proof WHERE id = ?",
                UUID.class, proof.id())).isEqualTo(operationId);
    }

    @Test
    void internalConsumeRejectsOperationProofAndOwnershipConflicts() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());
        SmsProofService.IssuedProof consumed = service.issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);
        SmsProofService.IssuedProof other = service.issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);
        service.consumeForOperation(
                consumed.token(), tenantId, userId, SmsPurpose.API_KEY_RESET, operationId);

        assertThatThrownBy(() -> service.consumeForOperation(
                consumed.token(), tenantId, userId, SmsPurpose.API_KEY_RESET, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consumeForOperation(
                other.token(), tenantId, userId, SmsPurpose.API_KEY_RESET, operationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consumeForOperation(
                consumed.token(), UUID.randomUUID(), userId,
                SmsPurpose.API_KEY_RESET, operationId))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consumeForOperation(
                consumed.token(), tenantId, UUID.randomUUID(),
                SmsPurpose.API_KEY_RESET, operationId))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consumeForOperation(
                consumed.token(), tenantId, userId,
                SmsPurpose.CHANGE_PASSWORD, operationId))
                .hasMessage("invalid_sms_proof");
    }

    @Test
    void rejectsExpiredMalformedAndPublicPurposeProofs() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());
        SmsProofService.IssuedProof proof = service.issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);
        clock.advance(Duration.ofMinutes(5).plusMillis(1));

        assertThatThrownBy(() -> service.consume(
                proof.token(), tenantId, userId, SmsPurpose.API_KEY_RESET))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.consume(
                "not-a-proof", tenantId, userId, SmsPurpose.API_KEY_RESET))
                .hasMessage("invalid_sms_proof");
        assertThatThrownBy(() -> service.issue(tenantId, userId, SmsPurpose.REGISTER))
                .hasMessage("sms_proof_purpose_not_authenticated");
    }

    @Test
    void consumesProofUsingOneAuthoritativeInstantAtExpiryBoundary() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService.IssuedProof proof = proofs(new SecureRandom()).issue(
                tenantId, userId, SmsPurpose.API_KEY_RESET);
        SmsProofService boundaryService = new SmsProofService(
                new SmsProofRepository(jdbc), transactions,
                new StepClock(proof.expiresAt().minusNanos(1), proof.expiresAt().plusNanos(1)),
                new SecureRandom(), Duration.ofMinutes(5));

        assertThat(boundaryService.consume(
                proof.token(), tenantId, userId, SmsPurpose.API_KEY_RESET).userId())
                .isEqualTo(userId);
    }

    @Test
    void concurrentConsumersCanConsumeProofOnlyOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsProofService service = proofs(new SecureRandom());
        SmsProofService.IssuedProof proof = service.issue(
                tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> consumeAfterBarrier(
                    service, proof.token(), tenantId, userId, ready, start));
            var second = workers.submit(() -> consumeAfterBarrier(
                    service, proof.token(), tenantId, userId, ready, start));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("consumed", "invalid_sms_proof");
        }
    }

    private String consumeAfterBarrier(
            SmsProofService service,
            String token,
            UUID tenantId,
            UUID userId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(2, TimeUnit.SECONDS);
        try {
            service.consume(token, tenantId, userId, SmsPurpose.PAYMENT_CONFIRM);
            return "consumed";
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    private static final class StepClock extends java.time.Clock {
        private final Instant[] values;
        private int index;

        private StepClock(Instant... values) {
            this.values = values;
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
