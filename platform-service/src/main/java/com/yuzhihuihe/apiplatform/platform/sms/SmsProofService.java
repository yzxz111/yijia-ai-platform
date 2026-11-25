package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class SmsProofService {

    private final SmsProofRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration lifetime;

    @Autowired
    SmsProofService(
            SmsProofRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${sms.proof-expiry:5m}") Duration lifetime) {
        this(repository, transactionManager, clock, new SecureRandom(), lifetime);
    }

    SmsProofService(
            SmsProofRepository repository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SecureRandom random,
            Duration lifetime) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.random = random;
        this.lifetime = requirePositive(lifetime);
    }

    public IssuedProof issue(UUID tenantId, UUID userId, SmsPurpose purpose) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(purpose, "purpose");
        if (!purpose.issuesProof()) {
            throw new IllegalArgumentException("sms_proof_purpose_not_authenticated");
        }
        return transactions.execute(ignored -> issueInTransaction(tenantId, userId, purpose));
    }

    IssuedProof issueInTransaction(UUID tenantId, UUID userId, SmsPurpose purpose) {
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(lifetime);
        repository.insert(id, hash(token), tenantId, userId, purpose, expiresAt, now);
        return new IssuedProof(id, token, expiresAt);
    }

    public ProofClaims consume(
            String token, UUID tenantId, UUID userId, SmsPurpose purpose) {
        if (!isTokenShapeValid(token) || tenantId == null || userId == null || purpose == null) {
            throw new IllegalArgumentException("invalid_sms_proof");
        }
        List<ProofClaims> claims = transactions.execute(ignored -> {
            Instant now = clock.instant();
            return repository.consume(hash(token), tenantId, userId, purpose, now).stream()
                    .map(this::claims).toList();
        });
        if (claims == null || claims.size() != 1) {
            throw new IllegalArgumentException("invalid_sms_proof");
        }
        return claims.getFirst();
    }

    public ProofClaims consumeForOperation(
            String token, UUID tenantId, UUID userId, SmsPurpose purpose, UUID operationId) {
        if (!isTokenShapeValid(token) || tenantId == null || userId == null
                || purpose == null || operationId == null) {
            throw new IllegalArgumentException("invalid_sms_proof");
        }
        String tokenHash = hash(token);
        List<ProofClaims> claims;
        try {
            claims = transactions.execute(ignored -> {
                Instant now = clock.instant();
                return repository.consumeForOperation(
                        tokenHash, tenantId, userId, purpose, operationId, now).stream()
                        .map(this::claims).toList();
            });
        } catch (DuplicateKeyException conflict) {
            throw new IllegalArgumentException("invalid_sms_proof");
        }
        if (claims == null || claims.size() != 1) {
            throw new IllegalArgumentException("invalid_sms_proof");
        }
        return claims.getFirst();
    }

    private boolean isTokenShapeValid(String token) {
        if (token == null) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(token).length == 32;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private ProofClaims claims(SmsProofRepository.ClaimsRow row) {
        return new ProofClaims(row.tenantId(), row.userId(), row.purpose());
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("sms_proof_hash_failed", error);
        }
    }

    private Duration requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("sms proof expiry must be positive");
        }
        return value;
    }

    public record IssuedProof(UUID id, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedProof[id=" + id + ", token=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }

    public record ProofClaims(UUID tenantId, UUID userId, SmsPurpose purpose) {
    }
}
