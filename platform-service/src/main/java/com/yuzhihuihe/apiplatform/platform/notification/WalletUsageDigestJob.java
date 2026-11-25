package com.yuzhihuihe.apiplatform.platform.notification;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class WalletUsageDigestJob {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long JOB_LOCK = 0x57414c4c4554444cL;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationEventService events;
    private final Clock clock;
    private final int catchupDays;

    @Autowired
    public WalletUsageDigestJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            NotificationEventService events,
            Clock clock,
            @Value("${notification.wallet-usage-digest.catchup-days:7}") int catchupDays) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
        this.clock = clock;
        if (catchupDays < 1 || catchupDays > 31) {
            throw new IllegalArgumentException("invalid_wallet_usage_digest_catchup_days");
        }
        this.catchupDays = catchupDays;
    }

    public WalletUsageDigestJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            NotificationEventService events,
            Clock clock) {
        this(jdbc, transactionManager, events, clock, 7);
    }

    @Scheduled(
            cron = "${NOTIFICATION_WALLET_DIGEST_CRON:${notification.wallet-usage-digest.cron:0 0 9 * * *}}",
            zone = "Asia/Shanghai")
    public void scheduledRun() {
        runOnce();
    }

    public int runOnce() {
        Integer result = transactions.execute(status -> runLocked());
        return result == null ? 0 : result;
    }

    private int runLocked() {
        Boolean locked = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, JOB_LOCK);
        if (!Boolean.TRUE.equals(locked)) {
            return 0;
        }
        LocalDate today = clock.instant().atZone(SHANGHAI).toLocalDate();
        Instant start = today.minusDays(catchupDays).atStartOfDay(SHANGHAI).toInstant();
        Instant end = today.atStartOfDay(SHANGHAI).toInstant();
        List<Summary> summaries = jdbc.query("""
                SELECT (usage.settled_at AT TIME ZONE 'Asia/Shanghai')::date,
                       usage.tenant_id, usage.user_id, sum(usage.settled_amount),
                       balance.available_amount
                FROM platform.wallet_usage_reservation usage
                JOIN platform.account_balance balance
                  ON balance.tenant_id=usage.tenant_id AND balance.user_id=usage.user_id
                 AND balance.currency='CNY'
                WHERE usage.status='SETTLED' AND usage.settled_amount>0
                  AND usage.billing_mode IN ('HYBRID','WALLET_ONLY')
                  AND usage.settled_at>=? AND usage.settled_at<?
                GROUP BY (usage.settled_at AT TIME ZONE 'Asia/Shanghai')::date,
                         usage.tenant_id, usage.user_id, balance.available_amount
                ORDER BY (usage.settled_at AT TIME ZONE 'Asia/Shanghai')::date,
                         usage.tenant_id, usage.user_id
                """, (result, row) -> new Summary(
                result.getObject(1, LocalDate.class), result.getObject(2, UUID.class),
                result.getObject(3, UUID.class), result.getLong(4), result.getLong(5)),
                Timestamp.from(start), Timestamp.from(end));
        int created = 0;
        for (Summary summary : summaries) {
            if (alreadyCreated(summary)) {
                continue;
            }
            LocalDate digestDate = summary.digestDate();
            NotificationEventService.RecordOutcome outcome =
                    events.recordAuthoritativeBusinessEvent(
                            "wallet-usage-digest:" + summary.userId() + ":" + digestDate,
                            "WALLET_USAGE_DAILY_SUMMARY",
                            summary.tenantId(),
                            summary.userId(),
                            "WALLET_USAGE_DAILY",
                            summary.userId() + ":" + digestDate,
                            Map.of(
                                    "costMoney", yuan(summary.amountFen()),
                                    "balance", yuan(summary.balanceFen())),
                            digestDate.plusDays(1).atStartOfDay(SHANGHAI)
                                    .withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime());
            jdbc.update("""
                    UPDATE platform.notification_trigger_state
                    SET last_digest_date=CASE
                            WHEN last_digest_date IS NULL OR last_digest_date < ? THEN ?
                            ELSE last_digest_date END,
                        last_triggered_at=?, updated_at=now()
                    WHERE user_id=? AND trigger_type='DAILY_DIGEST' AND state_key='DEFAULT'
                    """, Date.valueOf(digestDate), Date.valueOf(digestDate),
                    Timestamp.from(clock.instant()), summary.userId());
            if (outcome.created()) {
                created++;
            }
        }
        return created;
    }

    private boolean alreadyCreated(Summary summary) {
        jdbc.update("""
                INSERT INTO platform.notification_trigger_state(
                    tenant_id, user_id, trigger_type, state_key, state)
                VALUES (?, ?, 'DAILY_DIGEST', 'DEFAULT', '{}'::jsonb)
                ON CONFLICT (user_id, trigger_type, state_key) DO NOTHING
                """, summary.tenantId(), summary.userId());
        jdbc.queryForObject("""
                SELECT id FROM platform.notification_trigger_state
                WHERE user_id=? AND trigger_type='DAILY_DIGEST' AND state_key='DEFAULT'
                FOR UPDATE
                """, UUID.class, summary.userId());
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform.notification_event WHERE event_key=?)
                """, Boolean.class,
                "wallet-usage-digest:" + summary.userId() + ":" + summary.digestDate()));
    }

    private String yuan(long fen) {
        return BigDecimal.valueOf(fen, 2).setScale(2).toPlainString();
    }

    private record Summary(
            LocalDate digestDate, UUID tenantId, UUID userId, long amountFen, long balanceFen) { }
}
