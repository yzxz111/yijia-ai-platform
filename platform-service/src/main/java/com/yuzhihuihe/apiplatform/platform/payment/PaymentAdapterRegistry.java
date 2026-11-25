package com.yuzhihuihe.apiplatform.platform.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class PaymentAdapterRegistry {

    private final Map<PaymentProvider, Map<PaymentProduct, PaymentAdapter>> adapters;

    public PaymentAdapterRegistry(List<PaymentAdapter> registrations) {
        EnumMap<PaymentProvider, Map<PaymentProduct, PaymentAdapter>> byProvider =
                new EnumMap<>(PaymentProvider.class);
        for (PaymentAdapter adapter : List.copyOf(registrations)) {
            Objects.requireNonNull(adapter, "adapter");
            PaymentProvider provider = Objects.requireNonNull(adapter.provider(), "provider");
            Objects.requireNonNull(adapter.environment(), "environment");
            for (PaymentProduct product : Set.copyOf(
                    Objects.requireNonNull(adapter.products(), "products"))) {
                if (product.provider() != provider) {
                    throw new IllegalStateException("payment_product_provider_mismatch");
                }
                Map<PaymentProduct, PaymentAdapter> products = byProvider.computeIfAbsent(
                        provider, ignored -> new EnumMap<>(PaymentProduct.class));
                if (products.putIfAbsent(product, adapter) != null) {
                    throw new IllegalStateException("duplicate_payment_adapter_registration");
                }
            }
        }
        EnumMap<PaymentProvider, Map<PaymentProduct, PaymentAdapter>> immutable =
                new EnumMap<>(PaymentProvider.class);
        byProvider.forEach((provider, products) -> immutable.put(provider, Map.copyOf(products)));
        this.adapters = Map.copyOf(immutable);
    }

    public PaymentAdapter require(PaymentProvider provider, PaymentProduct product) {
        if (provider == null || product == null || product.provider() != provider) {
            throw new IllegalArgumentException("payment_product_provider_mismatch");
        }
        return find(provider, product)
                .orElseThrow(() -> new IllegalStateException("payment_adapter_not_configured"));
    }

    public Optional<PaymentAdapter> find(PaymentProvider provider, PaymentProduct product) {
        if (provider == null || product == null || product.provider() != provider) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.getOrDefault(provider, Map.of()).get(product));
    }

    public Optional<PaymentAdapter> find(
            PaymentProvider provider, PaymentEnvironment environment) {
        if (provider == null || environment == null) {
            return Optional.empty();
        }
        return adapters.getOrDefault(provider, Map.of()).values().stream()
                .filter(adapter -> adapter.environment() == environment)
                .findFirst();
    }
}
