package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删除已过期的授权码、已废弃的登录事务以及旧的刷新令牌。
 * 仅返回数量；令牌材料绝不写入日志。
 */
@Component
class OAuthCleanupJob {

    private final OAuthAuthorizationCodeRepository codes;
    private final OAuthRefreshTokenRepository refreshTokens;
    private final OAuthLoginTransactionRepository transactions;
    private final Clock clock;
    private final Duration codeRetention;
    private final Duration rotationRetention;
    private final Duration revokedRetention;

    @org.springframework.beans.factory.annotation.Autowired
    OAuthCleanupJob(
            OAuthAuthorizationCodeRepository codes,
            OAuthRefreshTokenRepository refreshTokens,
            OAuthLoginTransactionRepository transactions,
            @Value("${yuzhh.oauth.desktop.code-retention:1d}") Duration codeRetention,
            @Value("${yuzhh.oauth.desktop.rotation-retention:1d}") Duration rotationRetention,
            @Value("${yuzhh.oauth.desktop.revoked-retention:30d}") Duration revokedRetention) {
        this(codes, refreshTokens, transactions, codeRetention, rotationRetention,
                revokedRetention, Clock.systemUTC());
    }

    OAuthCleanupJob(
            OAuthAuthorizationCodeRepository codes,
            OAuthRefreshTokenRepository refreshTokens,
            OAuthLoginTransactionRepository transactions,
            Duration codeRetention,
            Duration rotationRetention,
            Duration revokedRetention,
            Clock clock) {
        this.codes = codes;
        this.refreshTokens = refreshTokens;
        this.transactions = transactions;
        this.codeRetention = codeRetention;
        this.rotationRetention = rotationRetention;
        this.revokedRetention = revokedRetention;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yuzhh.oauth.desktop.cleanup-delay:600000}")
    @Transactional
    void scheduledRun() {
        runOnce();
    }

    @Transactional
    CleanupResult runOnce() {
        Instant now = clock.instant();
        int codesDeleted = codes.deleteExpired(now, now.minus(codeRetention));
        int refreshTokensDeleted = refreshTokens.deleteRetired(now, now.minus(revokedRetention));
        int transactionsDeleted = transactions.deleteRetired(now, now.minus(rotationRetention));
        return new CleanupResult(codesDeleted, refreshTokensDeleted, transactionsDeleted);
    }

    record CleanupResult(int codesDeleted, int refreshTokensDeleted, int transactionsDeleted) { }
}
