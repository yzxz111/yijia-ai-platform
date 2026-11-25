package com.yuzhihuihe.apiplatform.platform.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityRegistrationRepositoryTest {

    @Test
    void initializesAnAuthoritativeClearedSafetyStateForNewAccounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdentityRegistrationRepository repository = new IdentityRegistrationRepository(jdbc);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        repository.createEmailPasswordAccount(userId, tenantId, "user@example.com", "encoded-hash");

        verify(jdbc).update(
                argThat(sql -> sql.contains("INSERT INTO gateway.safety_enforcement_cache")
                        && sql.contains("'CLEARED'")),
                any(UUID.class), eq(tenantId), eq(userId));
    }
}
