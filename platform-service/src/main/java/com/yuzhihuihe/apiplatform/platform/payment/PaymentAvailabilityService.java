package com.yuzhihuihe.apiplatform.platform.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentAvailabilityService {

    private final PaymentAvailabilityRepository repository;
    private final PaymentAdapterRegistry registry;
    private final TransactionTemplate safeMetadataTransactions;

    PaymentAvailabilityService(
            PaymentAvailabilityRepository repository,
            PaymentAdapterRegistry registry,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.safeMetadataTransactions = new TransactionTemplate(transactionManager);
        this.safeMetadataTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PaymentAdapter requireCheckout(
            PaymentProvider provider,
            PaymentProduct product) {
        PaymentAdapter adapter = requireAdapter(provider, product);
        PaymentAdapter.AdapterConfiguration configuration = configuration(adapter);
        synchronize(adapter, configuration);
        if (!configuration.healthy() || !adapter.supportsCheckout(product) || !databaseEnabled(adapter)) {
            throw unavailable("payment_checkout_unavailable");
        }
        return adapter;
    }

    public PaymentAdapter requireOperational(
            PaymentProvider provider,
            PaymentProduct product) {
        PaymentAdapter adapter = requireAdapter(provider, product);
        PaymentAdapter.AdapterConfiguration configuration = configuration(adapter);
        synchronize(adapter, configuration);
        if (!configuration.configured()) {
            throw unavailable("payment_adapter_not_configured");
        }
        return adapter;
    }

    public void assertCanEnable(
            PaymentProvider provider,
            PaymentEnvironment environment) {
        PaymentAdapter adapter = registry.find(provider, environment)
                .orElseThrow(() -> unavailable("payment_adapter_unhealthy"));
        PaymentAdapter.AdapterConfiguration configuration = configuration(adapter);
        synchronize(adapter, configuration);
        if (!configuration.healthy()) {
            throw unavailable("payment_adapter_unhealthy");
        }
    }

    private PaymentAdapter.AdapterConfiguration configuration(PaymentAdapter adapter) {
        PaymentAdapter.AdapterConfiguration configuration = adapter.configuration();
        if (configuration == null) {
            throw unavailable("payment_adapter_not_configured");
        }
        return configuration;
    }

    private PaymentAdapter requireAdapter(
            PaymentProvider provider,
            PaymentProduct product) {
        try {
            return registry.require(provider, product);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw unavailable(exception.getMessage());
        }
    }

    private boolean databaseEnabled(PaymentAdapter adapter) {
        return repository.isEnabled(adapter.provider(), adapter.environment());
    }

    private void synchronize(
            PaymentAdapter adapter,
            PaymentAdapter.AdapterConfiguration configuration) {
        safeMetadataTransactions.executeWithoutResult(
                ignored -> repository.synchronizeConfiguration(
                        adapter.provider(), adapter.environment(), configuration));
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
