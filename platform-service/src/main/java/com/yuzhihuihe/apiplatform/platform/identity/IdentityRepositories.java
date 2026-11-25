package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdentityRepositories {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final IdentityAuthenticationRepository authentication;
    private final IdentityContactRepository contact;
    private final IdentityProfileRepository profile;
    private final TerminalSessionRepository terminalSession;
    private final IdentityPasswordRepository password;
    private final IdentityRegistrationRepository registration;

    IdentityRepositories(
            IdentityAuthenticationRepository authentication,
            IdentityContactRepository contact,
            IdentityProfileRepository profile,
            TerminalSessionRepository terminalSession,
            IdentityPasswordRepository password,
            IdentityRegistrationRepository registration) {
        this.authentication = authentication;
        this.contact = contact;
        this.profile = profile;
        this.terminalSession = terminalSession;
        this.password = password;
        this.registration = registration;
    }

    public static IdentityRepositories forJdbc(JdbcTemplate jdbc) {
        return new IdentityRepositories(
                new IdentityAuthenticationRepository(jdbc),
                new IdentityContactRepository(jdbc),
                new IdentityProfileRepository(jdbc),
                new TerminalSessionRepository(jdbc),
                new IdentityPasswordRepository(jdbc),
                new IdentityRegistrationRepository(jdbc));
    }

    IdentityAuthenticationRepository authentication() {
        return authentication;
    }

    IdentityContactRepository contact() {
        return contact;
    }

    IdentityProfileRepository profile() {
        return profile;
    }

    TerminalSessionRepository terminalSession() {
        return terminalSession;
    }

    IdentityPasswordRepository password() {
        return password;
    }

    IdentityRegistrationRepository registration() {
        return registration;
    }

    boolean violatesUniqueConstraint(Throwable error, String constraint) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
                String message = sql.getMessage();
                if (message != null && message.contains("\"" + constraint + "\"")) {
                    return true;
                }
            }
        }
        return false;
    }
}
