package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BrowserSessionRepository {
    private final JdbcTemplate jdbc;

    BrowserSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean recordEvent(UUID sessionId, UUID tenantId, UUID userId, String deviceHash,
            UUID pageId, long sequence, boolean closed) {
        // Serialize page changes and cleanup on the same terminal row.
        var active = jdbc.query("""
                SELECT id FROM platform.terminal_session
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND device_hash = ?
                  AND client_type = 'DESKTOP_WEB' AND status = 'ACTIVE' AND expires_at > now()
                FOR UPDATE
                """, (rs, n) -> rs.getObject(1, UUID.class), sessionId, tenantId, userId, deviceHash);
        if (active.isEmpty()) return false;
        jdbc.update("""
                INSERT INTO platform.terminal_browser_page
                    (terminal_session_id, page_id, event_sequence, closed) VALUES (?, ?, ?, ?)
                ON CONFLICT (terminal_session_id, page_id) DO UPDATE
                SET event_sequence = EXCLUDED.event_sequence, closed = EXCLUDED.closed
                WHERE terminal_browser_page.event_sequence < EXCLUDED.event_sequence
                  AND (NOT terminal_browser_page.closed OR EXCLUDED.closed)
                """, sessionId, pageId, sequence, closed);
        // A delayed heartbeat cannot reopen a closed document; a new document has a new page ID.
        jdbc.update("""
                UPDATE platform.terminal_session SET browser_close_requested_at =
                    CASE WHEN EXISTS (SELECT 1 FROM platform.terminal_browser_page
                        WHERE terminal_session_id = ? AND NOT closed)
                    THEN NULL ELSE COALESCE(browser_close_requested_at, now()) END
                WHERE id = ?
                """, sessionId, sessionId);
        return true;
    }

    void deleteInactivePages() {
        jdbc.update("""
                DELETE FROM platform.terminal_browser_page p USING platform.terminal_session t
                WHERE p.terminal_session_id = t.id AND (t.status <> 'ACTIVE' OR t.expires_at <= now())
                """);
    }
}
