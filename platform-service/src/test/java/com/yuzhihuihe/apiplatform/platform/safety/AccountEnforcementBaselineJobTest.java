package com.yuzhihuihe.apiplatform.platform.safety;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class AccountEnforcementBaselineJobTest {

    @Test
    void synchronizesMissingBaselinesAndRetriesAfterRepositoryFailures() {
        AccountEnforcementRepository repository = mock(AccountEnforcementRepository.class);
        when(repository.initializeMissingGatewayCacheBaselines())
                .thenThrow(new IllegalStateException("gateway schema unavailable"))
                .thenReturn(2);
        AccountEnforcementBaselineJob job = new AccountEnforcementBaselineJob(repository);

        job.synchronize();
        job.synchronize();
        job.synchronize();

        verify(repository, org.mockito.Mockito.times(2))
                .initializeMissingGatewayCacheBaselines();
    }
}
