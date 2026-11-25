package com.yuzhihuihe.apiplatform.platform.wallet;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AdminWalletRepository {

    private final JdbcTemplate jdbc;

    AdminWalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PageRows<AccountRow> findAccounts(
            UUID tenantId, String query, String status, Instant from, Instant to,
            int size, int offset, AccountSort sort, SortDirection direction) {
        List<Object> filters = new ArrayList<>();
        String where = readScope("account", tenantId, filters);
        if (query != null) {
            where += " AND (position(lower(?) in lower(app_user.email)) > 0"
                    + " OR position(lower(?) in lower(app_user.user_no)) > 0"
                    + " OR position(lower(?) in lower(app_user.display_name)) > 0"
                    + " OR position(lower(?) in lower(app_user.id::text)) > 0)";
            addRepeated(filters, query, 4);
        }
        if (status != null) {
            where += " AND app_user.status = ?";
            filters.add(status);
        }
        if (from != null) {
            where += " AND account.updated_at >= ?";
            filters.add(Timestamp.from(from));
        }
        if (to != null) {
            where += " AND account.updated_at <= ?";
            filters.add(Timestamp.from(to));
        }
        long total = count("""
                SELECT count(*)
                FROM platform.account_balance AS account
                JOIN platform.app_user AS app_user
                  ON app_user.tenant_id = account.tenant_id
                 AND app_user.id = account.user_id
                """ + where, filters);
        List<Object> arguments = pageArguments(filters, size, offset);
        List<AccountRow> items = jdbc.query("""
                SELECT account.id, account.user_id, app_user.user_no, app_user.email,
                       app_user.display_name, account.currency,
                       account.available_amount, account.version,
                       account.created_at, account.updated_at
                FROM platform.account_balance AS account
                JOIN platform.app_user AS app_user
                  ON app_user.tenant_id = account.tenant_id
                 AND app_user.id = account.user_id
                """ + where + ordering(sort.column, direction, "account")
                + " LIMIT ? OFFSET ?", (result, row) -> new AccountRow(
                result.getObject("id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getString("user_no"),
                result.getString("email"),
                result.getString("display_name"),
                result.getString("currency"),
                result.getLong("available_amount"),
                result.getLong("version"),
                instant(result, "created_at"),
                instant(result, "updated_at")), arguments.toArray());
        return new PageRows<>(items, total);
    }

    PageRows<LedgerRow> findLedger(
            UUID tenantId, String query, String entryType, Instant from, Instant to,
            int size, int offset, LedgerSort sort, SortDirection direction) {
        List<Object> filters = new ArrayList<>();
        String where = readScope("ledger", tenantId, filters);
        if (query != null) {
            where += " AND (position(lower(?) in lower(app_user.email)) > 0"
                    + " OR position(lower(?) in lower(app_user.user_no)) > 0"
                    + " OR position(lower(?) in lower(app_user.display_name)) > 0"
                    + " OR position(lower(?) in lower(app_user.id::text)) > 0"
                    + " OR position(lower(?) in lower(ledger.entry_no)) > 0"
                    + " OR position(lower(?) in lower(ledger.business_no)) > 0)";
            addRepeated(filters, query, 6);
        }
        if (entryType != null) {
            where += " AND ledger.entry_type = ?";
            filters.add(entryType);
        }
        if (from != null) {
            where += " AND ledger.created_at >= ?";
            filters.add(Timestamp.from(from));
        }
        if (to != null) {
            where += " AND ledger.created_at <= ?";
            filters.add(Timestamp.from(to));
        }
        String fromSql = """
                FROM platform.balance_ledger AS ledger
                JOIN platform.account_balance AS account
                  ON account.tenant_id = ledger.tenant_id
                 AND account.user_id = ledger.user_id
                 AND account.id = ledger.account_id
                JOIN platform.app_user AS app_user
                  ON app_user.tenant_id = ledger.tenant_id
                 AND app_user.id = ledger.user_id
                """;
        long total = count("SELECT count(*) " + fromSql + where, filters);
        List<Object> arguments = pageArguments(filters, size, offset);
        List<LedgerRow> items = jdbc.query("""
                SELECT ledger.entry_no, ledger.user_id, app_user.user_no, app_user.email,
                       app_user.display_name,
                       ledger.entry_type, ledger.amount_delta, ledger.balance_after,
                       ledger.business_no, ledger.description, ledger.created_at
                """ + fromSql + where + ordering(sort.column, direction, "ledger")
                + " LIMIT ? OFFSET ?", (result, row) -> new LedgerRow(
                result.getString("entry_no"),
                result.getObject("user_id", UUID.class),
                result.getString("user_no"),
                result.getString("email"),
                result.getString("display_name"),
                result.getString("entry_type"),
                result.getLong("amount_delta"),
                result.getLong("balance_after"),
                result.getString("business_no"),
                result.getString("description"),
                instant(result, "created_at")), arguments.toArray());
        return new PageRows<>(items, total);
    }

    PageRows<RechargeRow> findRecharges(
            UUID tenantId, String query, String status, Instant from, Instant to,
            int size, int offset, RechargeSort sort, SortDirection direction) {
        List<Object> filters = new ArrayList<>();
        String where = readScope("recharge", tenantId, filters);
        if (query != null) {
            where += " AND (position(lower(?) in lower(app_user.email)) > 0"
                    + " OR position(lower(?) in lower(app_user.user_no)) > 0"
                    + " OR position(lower(?) in lower(app_user.display_name)) > 0"
                    + " OR position(lower(?) in lower(app_user.id::text)) > 0"
                    + " OR position(lower(?) in lower(recharge.recharge_no)) > 0)";
            addRepeated(filters, query, 5);
        }
        if (status != null) {
            where += " AND recharge.status = ?";
            filters.add(status);
        }
        if (from != null) {
            where += " AND recharge.created_at >= ?";
            filters.add(Timestamp.from(from));
        }
        if (to != null) {
            where += " AND recharge.created_at <= ?";
            filters.add(Timestamp.from(to));
        }
        String fromSql = """
                FROM platform.recharge_order AS recharge
                JOIN platform.app_user AS app_user
                  ON app_user.tenant_id = recharge.tenant_id
                 AND app_user.id = recharge.user_id
                """;
        long total = count("SELECT count(*) " + fromSql + where, filters);
        List<Object> arguments = pageArguments(filters, size, offset);
        List<RechargeRow> items = jdbc.query("""
                SELECT recharge.recharge_no, recharge.user_id, app_user.user_no, app_user.email,
                       app_user.display_name,
                       recharge.option_id, recharge.paid_amount, recharge.bonus_amount,
                       recharge.credited_amount, recharge.currency, recharge.status,
                       recharge.expires_at, recharge.paid_at, recharge.closed_at,
                       recharge.created_at, recharge.updated_at
                """ + fromSql + where + ordering(sort.column, direction, "recharge")
                + " LIMIT ? OFFSET ?", (result, row) -> new RechargeRow(
                result.getString("recharge_no"),
                result.getObject("user_id", UUID.class),
                result.getString("user_no"),
                result.getString("email"),
                result.getString("display_name"),
                result.getObject("option_id", UUID.class),
                result.getLong("paid_amount"),
                result.getLong("bonus_amount"),
                result.getLong("credited_amount"),
                result.getString("currency"),
                result.getString("status"),
                instant(result, "expires_at"),
                instant(result, "paid_at"),
                instant(result, "closed_at"),
                instant(result, "created_at"),
                instant(result, "updated_at")), arguments.toArray());
        return new PageRows<>(items, total);
    }

    boolean isGlobalAdmin(UUID tenantId, UUID actorUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM platform.app_user u
                    JOIN platform.user_role ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
                    JOIN platform.app_role r ON r.id = ur.role_id AND r.code = 'ADMIN'
                    WHERE u.tenant_id = ? AND u.id = ? AND u.status = 'ACTIVE' AND u.global_admin)
                """, Boolean.class, tenantId, actorUserId));
    }

    private String readScope(String alias, UUID tenantId, List<Object> arguments) {
        if (tenantId == null) return " WHERE 1 = 1";
        arguments.add(tenantId);
        return " WHERE " + alias + ".tenant_id = ?";
    }

    BalanceTotals balanceTotals(UUID tenantId) {
        List<Object> arguments = new ArrayList<>();
        String where = readScope("account", tenantId, arguments);
        return jdbc.queryForObject("""
                SELECT count(*) accounts, coalesce(sum(available_amount), 0) total_balance
                FROM platform.account_balance account
                """ + where, (rs, row) -> new BalanceTotals(rs.getLong("accounts"),
                rs.getLong("total_balance")), arguments.toArray());
    }

    List<DailyFlow> dailyFlows(UUID tenantId, Instant from, Instant to) {
        List<Object> arguments = new ArrayList<>();
        String where = readScope("ledger", tenantId, arguments);
        arguments.add(Timestamp.from(from));
        arguments.add(Timestamp.from(to));
        return jdbc.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Shanghai')::date business_date,
                       coalesce(sum(amount_delta) FILTER (WHERE entry_type = 'RECHARGE'), 0) recharge,
                       coalesce(-sum(amount_delta) FILTER (
                           WHERE entry_type IN ('PACKAGE_PURCHASE', 'MODEL_USAGE')), 0) debit
                FROM platform.balance_ledger ledger
                """ + where + " AND created_at >= ? AND created_at < ? GROUP BY 1 ORDER BY 1",
                (rs, row) -> new DailyFlow(rs.getObject("business_date", LocalDate.class),
                        rs.getLong("recharge"), rs.getLong("debit")), arguments.toArray());
    }

    record BalanceTotals(long accounts, long totalBalance) {}
    record DailyFlow(LocalDate date, long recharge, long debit) {}

    List<OptionRow> findOptions(UUID tenantId) {
        return jdbc.query("""
                SELECT id, amount, enabled, display_order, created_at, updated_at
                FROM platform.recharge_option
                WHERE tenant_id = ?
                ORDER BY display_order, amount, id
                """, (result, row) -> option(result), tenantId);
    }

    void lockOptions(UUID tenantId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "wallet-options:" + tenantId);
                statement.execute();
                return null;
            }
        });
    }

    boolean activeActor(UUID tenantId, UUID actorUserId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.app_user
                    WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE')
                """, Boolean.class, tenantId, actorUserId));
    }

    void disableOptions(UUID tenantId, UUID actorUserId) {
        jdbc.update("""
                UPDATE platform.recharge_option
                SET enabled = false, updated_by = ?, updated_at = now()
                WHERE tenant_id = ?
                """, actorUserId, tenantId);
    }

    void upsertOption(
            UUID id, UUID tenantId, long amount, boolean enabled,
            int displayOrder, UUID actorUserId) {
        jdbc.update("""
                INSERT INTO platform.recharge_option(
                    id, tenant_id, amount, enabled, display_order,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, amount) DO UPDATE
                SET enabled = EXCLUDED.enabled,
                    display_order = EXCLUDED.display_order,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """, id, tenantId, amount, enabled, displayOrder, actorUserId, actorUserId);
    }

    void insertReplacementAudit(
            UUID tenantId, UUID actorUserId, int optionCount, long enabledCount) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'WALLET_RECHARGE_OPTIONS_REPLACED',
                        'RECHARGE_OPTION_SET', ?, 'SUCCESS',
                        jsonb_build_object('optionCount', ?, 'enabledCount', ?))
                """, tenantId, actorUserId, tenantId.toString(), optionCount, enabledCount);
    }

    private long count(String sql, List<Object> arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments.toArray());
    }

    private List<Object> pageArguments(List<Object> filters, int size, int offset) {
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(size);
        arguments.add(offset);
        return arguments;
    }

    private void addRepeated(List<Object> filters, Object value, int count) {
        for (int index = 0; index < count; index++) {
            filters.add(value);
        }
    }

    private String ordering(String column, SortDirection direction, String alias) {
        return " ORDER BY " + column + " " + direction + ", " + alias + ".id " + direction;
    }

    private OptionRow option(ResultSet result) throws SQLException {
        return new OptionRow(
                result.getObject("id", UUID.class),
                result.getLong("amount"),
                result.getBoolean("enabled"),
                result.getInt("display_order"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum AccountSort {
        CREATED_AT("account.created_at"),
        UPDATED_AT("account.updated_at"),
        AVAILABLE_AMOUNT("account.available_amount"),
        EMAIL("app_user.email"),
        USER_NO("app_user.user_no");

        private final String column;

        AccountSort(String column) {
            this.column = column;
        }
    }

    enum LedgerSort {
        CREATED_AT("ledger.created_at"),
        AMOUNT_DELTA("ledger.amount_delta"),
        BALANCE_AFTER("ledger.balance_after"),
        ENTRY_TYPE("ledger.entry_type");

        private final String column;

        LedgerSort(String column) {
            this.column = column;
        }
    }

    enum RechargeSort {
        CREATED_AT("recharge.created_at"),
        UPDATED_AT("recharge.updated_at"),
        PAID_AMOUNT("recharge.paid_amount"),
        STATUS("recharge.status");

        private final String column;

        RechargeSort(String column) {
            this.column = column;
        }
    }

    enum SortDirection {
        ASC,
        DESC
    }

    record PageRows<T>(List<T> items, long total) {
    }

    record AccountRow(
            UUID id, UUID userId, String userNo, String email, String displayName,
            String currency, long availableAmount, long version,
            Instant createdAt, Instant updatedAt) {
    }

    record LedgerRow(
            String entryNo, UUID userId, String userNo, String email, String displayName,
            String entryType, long amountDelta, long balanceAfter,
            String businessNo, String description, Instant createdAt) {
    }

    record RechargeRow(
            String rechargeNo, UUID userId, String userNo, String email, String displayName,
            UUID optionId, long paidAmount, long bonusAmount, long creditedAmount,
            String currency, String status, Instant expiresAt, Instant paidAt,
            Instant closedAt, Instant createdAt, Instant updatedAt) {
    }

    record OptionRow(
            UUID id, long amount, boolean enabled, int displayOrder,
            Instant createdAt, Instant updatedAt) {
    }
}
