package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps payment operations on the application recorded before contacting Alipay. */
public final class AlipayApplicationAdapter implements PaymentAdapter, AutoCloseable {
    private final String desktopAppId;
    private final String mobileAppId;
    private final Map<String, PaymentAdapter> applications;
    private final ApplicationBindings bindings;
    private final boolean mobileCheckoutEnabled;
    private final AdapterConfiguration configuration;
    private final Runnable closeMobile;

    public AlipayApplicationAdapter(
            String desktopAppId,
            PaymentAdapter desktop,
            String mobileAppId,
            PaymentAdapter mobile,
            ApplicationBindings bindings,
            boolean mobileCheckoutEnabled,
            Clock clock,
            Runnable closeMobile) {
        if (desktopAppId.equals(mobileAppId)) {
            throw new IllegalArgumentException("alipay_mobile_application_must_be_distinct");
        }
        this.desktopAppId = desktopAppId;
        this.mobileAppId = mobileAppId;
        this.applications = Map.of(desktopAppId, desktop, mobileAppId, mobile);
        this.bindings = bindings;
        this.mobileCheckoutEnabled = mobileCheckoutEnabled;
        this.closeMobile = closeMobile;
        this.configuration =
                new AdapterConfiguration(
                        true,
                        hash(
                                desktop.configuration().credentialFingerprint()
                                        + ":"
                                        + mobile.configuration().credentialFingerprint()),
                        "VALID",
                        clock.instant());
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.ALIPAY;
    }

    @Override
    public PaymentEnvironment environment() {
        return PaymentEnvironment.PRODUCTION;
    }

    @Override
    public Set<PaymentProduct> products() {
        return Set.of(PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP);
    }

    @Override
    public boolean supportsCheckout(PaymentProduct product) {
        return product == PaymentProduct.ALIPAY_PAGE
                || product == PaymentProduct.ALIPAY_WAP && mobileCheckoutEnabled;
    }

    @Override
    public AdapterConfiguration configuration() {
        return configuration;
    }

    @Override
    public PaymentCreation createPayment(PaymentCommand command) {
        if (!supportsCheckout(command.product())) {
            throw new IllegalStateException("payment_checkout_unavailable");
        }
        String appId = command.product() == PaymentProduct.ALIPAY_WAP ? mobileAppId : desktopAppId;
        String boundApp = bindings.bind(command.transactionNo(), appId);
        if (!appId.equals(boundApp)) {
            throw new IllegalStateException("alipay_payment_application_mismatch");
        }
        return application(boundApp).createPayment(command);
    }

    @Override
    public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
        for (String appId : List.of(desktopAppId, mobileAppId)) {
            ProviderPayment payment;
            try {
                // Every delegate verifies the original signature, APPID, seller and amount.
                payment = application(appId).verifyAndParseCallback(callback);
            } catch (ResponseStatusException invalid) {
                continue;
            }
            if (!appId.equals(bindings.find(payment.transactionNo()))) {
                throw invalidCallback();
            }
            return payment;
        }
        throw invalidCallback();
    }

    @Override
    public ProviderPayment queryPayment(PaymentQuery query) {
        return forTransaction(query.transactionNo()).queryPayment(query);
    }

    @Override
    public ProviderPayment closePayment(PaymentQuery query) {
        return forTransaction(query.transactionNo()).closePayment(query);
    }

    @Override
    public ProviderRefund createRefund(RefundCommand command) {
        return forTransaction(command.transactionNo()).createRefund(command);
    }

    @Override
    public ProviderRefund queryRefund(RefundQuery query) {
        return forTransaction(query.transactionNo()).queryRefund(query);
    }

    @Override
    public BillDownload downloadBill(LocalDate billDate) {
        BillDownload desktop = application(desktopAppId).downloadBill(billDate);
        if (!mobileCheckoutEnabled && !bindings.hasPayments(mobileAppId)) return desktop;
        BillDownload mobile = application(mobileAppId).downloadBill(billDate);
        Map<String, BillEntry> entries = new LinkedHashMap<>();
        for (BillDownload bill : List.of(desktop, mobile)) {
            if (bill.outcome() != ProviderOutcome.SUCCEEDED
                    && bill.outcome() != ProviderOutcome.NOT_FOUND) {
                return new BillDownload(bill.outcome(), billDate, null, List.of());
            }
            for (BillEntry entry : bill.entries()) {
                String key = entry.entryType() + ":" + entry.providerRecordId();
                BillEntry existing = entries.putIfAbsent(key, entry);
                if (existing != null && !existing.equals(entry)) {
                    throw new IllegalStateException("conflicting_alipay_application_bills");
                }
            }
        }
        if (desktop.outcome() == ProviderOutcome.NOT_FOUND
                && mobile.outcome() == ProviderOutcome.NOT_FOUND) {
            return new BillDownload(ProviderOutcome.NOT_FOUND, billDate, null, List.of());
        }
        return new BillDownload(
                ProviderOutcome.SUCCEEDED,
                billDate,
                hash(desktop.sha256() + ":" + mobile.sha256()),
                List.copyOf(entries.values()));
    }

    private PaymentAdapter forTransaction(String transactionNo) {
        return application(bindings.find(transactionNo));
    }

    private PaymentAdapter application(String appId) {
        PaymentAdapter adapter = applications.get(appId);
        if (adapter == null)
            throw new IllegalStateException("alipay_payment_application_not_configured");
        return adapter;
    }

    private ResponseStatusException invalidCallback() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_alipay_callback");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void close() {
        closeMobile.run();
    }

    public interface ApplicationBindings {
        String bind(String transactionNo, String appId);

        String find(String transactionNo);

        boolean hasPayments(String appId);
    }
}
