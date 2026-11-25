package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentActionType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentEnvironment;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProduct;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentProvider;
import com.yuzhihuihe.apiplatform.platform.payment.ProviderOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AlipayAdapter implements PaymentAdapter {

    private static final int MAX_FORM_HTML_CHARS = 1024 * 1024;
    private static final int MAX_CALLBACK_PARAMETERS = 100;
    private static final Set<PaymentProduct> PRODUCTS = Set.of(
            PaymentProduct.ALIPAY_PAGE, PaymentProduct.ALIPAY_WAP);
    private static final Set<String> SANITIZED_FORM_ATTRIBUTES = Set.of("method", "action");
    private static final Set<String> SDK_FORM_ATTRIBUTES = Set.of("name", "method", "action");
    private static final Set<String> HIDDEN_INPUT_ATTRIBUTES = Set.of("type", "name", "value");
    private static final Set<String> SDK_SUBMIT_ATTRIBUTES = Set.of("type", "value", "style");
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final AlipayGateway gateway;
    private final String appId;
    private final String sellerId;
    private final PublicKey alipayPublicKey;
    private final URI gatewayUrl;
    private final URI notifyUrl;
    private final URI returnUrl;
    private final AdapterConfiguration configuration;
    private final PaymentExpectationLookup paymentExpectationLookup;

    public AlipayAdapter(
            AlipayGateway gateway,
            Clock clock,
            String appId,
            String sellerId,
            PublicKey alipayPublicKey,
            URI gatewayUrl,
            URI notifyUrl,
            URI returnUrl,
            String credentialFingerprint,
            PaymentExpectationLookup paymentExpectationLookup) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(clock, "clock");
        this.appId = required(appId);
        this.sellerId = required(sellerId);
        this.alipayPublicKey = Objects.requireNonNull(alipayPublicKey, "alipayPublicKey");
        this.gatewayUrl = Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        this.notifyUrl = Objects.requireNonNull(notifyUrl, "notifyUrl");
        this.returnUrl = Objects.requireNonNull(returnUrl, "returnUrl");
        this.configuration = new AdapterConfiguration(
                true, required(credentialFingerprint), "VALID", clock.instant());
        this.paymentExpectationLookup = Objects.requireNonNull(
                paymentExpectationLookup, "paymentExpectationLookup");
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
        return PRODUCTS;
    }

    @Override
    public AdapterConfiguration configuration() {
        return configuration;
    }

    @Override
    public PaymentCreation createPayment(PaymentCommand command) {
        Objects.requireNonNull(command, "command");
        if (!PRODUCTS.contains(command.product())) {
            throw new IllegalArgumentException("payment_product_provider_mismatch");
        }
        AlipayGateway.PaymentRequest request = new AlipayGateway.PaymentRequest(
                appId, sellerId, command.transactionNo(), cents(command.amount()),
                command.currency(), command.orderNo(), notifyUrl, returnUrl, command.expiresAt());
        try {
            String form = command.product() == PaymentProduct.ALIPAY_PAGE
                    ? gateway.createPagePayment(request) : gateway.createWapPayment(request);
            ParsedForm parsed = parsePaymentForm(form);
            return new PaymentCreation(
                    ProviderOutcome.PENDING, null, "FORM_CREATED",
                    new PaymentAction(PaymentActionType.HTML_FORM, parsed.action(),
                            parsed.fields(), null, command.expiresAt()));
        } catch (AlipayGateway.GatewayException exception) {
            return failedCreation(command.expiresAt(), exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return uncertainCreation(command.expiresAt());
        }
    }

    @Override
    public ProviderPayment verifyAndParseCallback(CallbackEnvelope callback) {
        Objects.requireNonNull(callback, "callback");
        byte[] rawBody = callback.rawBody();
        try {
            Map<String, String> parameters = parseCallback(rawBody);
            if (!"RSA2".equals(parameters.get("sign_type"))) {
                throw invalidCallback();
            }
            String suppliedSignature = required(parameters.get("sign"));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(alipayPublicKey);
            verifier.update(callbackSignatureContent(parameters)
                    .getBytes(callbackCharset(parameters.get("charset"))));
            if (!verifier.verify(Base64.getDecoder().decode(suppliedSignature))) {
                throw invalidCallback();
            }
            return callbackPayment(parameters, sha256(rawBody));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCallback();
        }
    }

    @Override
    public ProviderPayment queryPayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        String transactionNo = query.transactionNo();
        try {
            return toProviderPayment(gateway.queryPayment(transactionNo), query);
        } catch (AlipayGateway.GatewayException exception) {
            return failedPayment(transactionNo, exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return unresolvedPayment(
                    ProviderOutcome.UNCERTAIN, transactionNo, "UNKNOWN", "UNKNOWN");
        }
    }

    @Override
    public ProviderPayment closePayment(PaymentQuery query) {
        Objects.requireNonNull(query, "query");
        String transactionNo = query.transactionNo();
        try {
            return toProviderPayment(gateway.closePayment(transactionNo), query);
        } catch (AlipayGateway.GatewayException exception) {
            return failedPayment(transactionNo, exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return unresolvedPayment(
                    ProviderOutcome.UNCERTAIN, transactionNo, "UNKNOWN", "UNKNOWN");
        }
    }

    @Override
    public ProviderRefund createRefund(RefundCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            AlipayGateway.RefundResult result = gateway.createRefund(
                    new AlipayGateway.RefundRequest(
                            appId, sellerId, command.transactionNo(), command.externalTradeNo(),
                            command.refundNo(), cents(command.totalAmount()),
                            cents(command.amount()), command.currency(), command.reason(), notifyUrl));
            return toProviderRefund(result, new RefundQuery(
                    command.transactionNo(), command.externalTradeNo(), command.refundNo(),
                    command.amount(), command.currency()));
        } catch (AlipayGateway.GatewayException exception) {
            return failedRefund(command.refundNo(), command.amount(), exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return new ProviderRefund(
                    ProviderOutcome.UNCERTAIN, command.refundNo(), null, command.amount(),
                    "UNKNOWN", "UNKNOWN");
        }
    }

    @Override
    public ProviderRefund queryRefund(RefundQuery query) {
        Objects.requireNonNull(query, "query");
        try {
            AlipayGateway.RefundQueryRequest request = new AlipayGateway.RefundQueryRequest(
                    appId, sellerId, query.transactionNo(), query.externalTradeNo(),
                    query.refundNo(), cents(query.amount()), query.currency());
            return toProviderRefund(gateway.queryRefund(request), query);
        } catch (AlipayGateway.GatewayException exception) {
            return failedRefund(query.refundNo(), query.amount(), exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return new ProviderRefund(
                    ProviderOutcome.UNCERTAIN, query.refundNo(), null, query.amount(), "UNKNOWN",
                    "UNKNOWN");
        }
    }

    @Override
    public BillDownload downloadBill(LocalDate billDate) {
        Objects.requireNonNull(billDate, "billDate");
        try {
            AlipayGateway.BillResult result = gateway.downloadBill(billDate);
            validateBill(result, billDate);
            List<BillEntry> entries = result.entries().stream()
                    .map(this::toBillEntry)
                    .toList();
            return new BillDownload(
                    ProviderOutcome.SUCCEEDED, billDate, required(result.sha256()), entries);
        } catch (AlipayGateway.GatewayException exception) {
            return new BillDownload(outcome(exception), billDate, null, List.of());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return new BillDownload(ProviderOutcome.UNCERTAIN, billDate, null, List.of());
        }
    }

    private ProviderPayment callbackPayment(Map<String, String> values, String payloadHash) {
        String status = values.get("trade_status");
        if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
            throw invalidCallback();
        }
        String transactionNo = callbackIdentifier(values.get("out_trade_no"), 64);
        String tradeNo = callbackIdentifier(values.get("trade_no"), 128);
        long amount = parseCents(values.get("total_amount"));
        Instant paidAt = parseCallbackTime(values.get("gmt_payment"));
        // Domestic page/WAP notifications may omit currency; total_amount is in CNY.
        // Resolve this only after verifying the original, unmodified parameters.
        String currency = values.getOrDefault("currency", "CNY");
        if (!appId.equals(values.get("app_id"))
                || !sellerId.equals(values.get("seller_id"))
                || !"CNY".equals(currency)
                || paidAt == null) {
            throw invalidCallback();
        }
        ExpectedPayment expected = Objects.requireNonNull(
                paymentExpectationLookup.require(transactionNo),
                "payment expectation lookup returned null");
        if (expected.amount() != amount
                || !expected.currency().equals(currency)) {
            throw invalidCallback();
        }
        return new ProviderPayment(
                ProviderOutcome.SUCCEEDED, transactionNo, tradeNo, amount, "CNY", paidAt,
                status, null, payloadHash);
    }

    private ProviderPayment toProviderPayment(
            AlipayGateway.PaymentResult result, PaymentQuery expected) {
        String expectedTransactionNo = expected.transactionNo();
        if (result == null || result.status() == null
                || !appId.equals(result.appId())
                || !sellerId.equals(result.sellerId())
                || !expectedTransactionNo.equals(result.merchantOrderNo())
                || !"CNY".equals(result.currency())) {
            throw invalidResponse();
        }
        long amount = responseCents(result.totalAmount());
        if (expected.amount() != amount || !expected.currency().equals(result.currency())) {
            throw invalidResponse();
        }
        ProviderOutcome outcome = paymentOutcome(result.status());
        String tradeNo = optional(result.tradeNo());
        if (outcome == ProviderOutcome.SUCCEEDED
                && (tradeNo == null || result.paidAt() == null)) {
            throw invalidResponse();
        }
        return new ProviderPayment(
                outcome, expectedTransactionNo, tradeNo, amount, "CNY",
                outcome == ProviderOutcome.SUCCEEDED ? result.paidAt() : null,
                result.status().name(), safeCode(result.providerCode()),
                sha256((expectedTransactionNo + "\n" + safeCode(result.providerCode()))
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private ProviderRefund toProviderRefund(
            AlipayGateway.RefundResult result, RefundQuery expected) {
        if (result == null || result.status() == null
                || !appId.equals(result.appId())
                || !sellerId.equals(result.sellerId())
                || !expected.refundNo().equals(result.merchantRefundNo())
                || !expected.transactionNo().equals(result.merchantOrderNo())
                || !expected.externalTradeNo().equals(result.tradeNo())
                || !"CNY".equals(result.currency())) {
            throw invalidResponse();
        }
        long amount = responseCents(result.refundAmount());
        if (expected.amount() != amount || !expected.currency().equals(result.currency())) {
            throw invalidResponse();
        }
        return new ProviderRefund(
                refundOutcome(result.status()), expected.refundNo(), optional(result.tradeNo()),
                amount, result.status().name(), safeCode(result.providerCode()));
    }

    private void validateBill(AlipayGateway.BillResult result, LocalDate billDate) {
        if (result == null || !billDate.equals(result.billDate())
                || !appId.equals(result.appId()) || !sellerId.equals(result.sellerId())
                || result.entries() == null) {
            throw invalidResponse();
        }
    }

    private BillEntry toBillEntry(AlipayGateway.BillLine line) {
        if (line == null || line.status() == null || !"CNY".equals(line.currency())) {
            throw invalidResponse();
        }
        return new BillEntry(
                required(line.providerRecordId()), Objects.requireNonNull(line.entryType()),
                optional(line.merchantOrderNo()), optional(line.merchantRefundNo()),
                responseCents(line.amount()), line.currency(), responseNonNegativeCents(line.feeAmount()),
                line.feeCurrency(), line.feeKnown(), paymentOutcome(line.status()),
                Objects.requireNonNull(line.occurredAt()));
    }

    private long responseNonNegativeCents(String value) {
        try {
            long cents = new BigDecimal(value).movePointRight(2).longValueExact();
            if (cents < 0) throw new ArithmeticException("negative amount");
            return cents;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private ParsedForm parsePaymentForm(String html) {
        return parsePaymentForm(html, false, gatewayUrl);
    }

    static String normalizeSdkForm(String html, URI gatewayUrl) {
        ParsedForm parsed = normalizeSdkAction(
                parsePaymentForm(html, true, gatewayUrl), gatewayUrl);
        StringBuilder sanitized = new StringBuilder("<form method=\"post\" action=\"")
                .append(escapeHtml(parsed.action().toString()))
                .append("\">\n");
        parsed.fields().forEach((name, value) -> sanitized
                .append("<input type=\"hidden\" name=\"")
                .append(escapeHtml(name))
                .append("\" value=\"")
                .append(escapeHtml(value))
                .append("\">\n"));
        return sanitized.append("</form>").toString();
    }

    private static ParsedForm normalizeSdkAction(ParsedForm parsed, URI gatewayUrl) {
        Map<String, String> fields = new LinkedHashMap<>(parsed.fields());
        String query = parsed.action().getRawQuery();
        if (query != null) {
            if (query.isBlank()) {
                throw new IllegalStateException("invalid_alipay_payment_response");
            }
            for (String pair : query.split("&", -1)) {
                int separator = pair.indexOf('=');
                if (separator <= 0 || separator != pair.lastIndexOf('=')) {
                    throw new IllegalStateException("invalid_alipay_payment_response");
                }
                String name = URLDecoder.decode(
                        pair.substring(0, separator), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(
                        pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (!PaymentAdapter.HTML_FORM_FIELDS.contains(name)
                        || value.isBlank() || value.length() > 4096
                        || fields.putIfAbsent(name, value) != null) {
                    throw new IllegalStateException("invalid_alipay_payment_response");
                }
            }
        }
        return new ParsedForm(gatewayUrl, Map.copyOf(fields));
    }

    private static ParsedForm parsePaymentForm(
            String html, boolean allowSdkSubmit, URI gatewayUrl) {
        if (html == null || html.isBlank() || html.length() > MAX_FORM_HTML_CHARS) {
            throw new IllegalStateException("invalid_alipay_payment_response");
        }
        try {
            FormParser parser = new FormParser(allowSdkSubmit, gatewayUrl);
            new ParserDelegator().parse(new StringReader(html), parser, true);
            return parser.result();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("invalid_alipay_payment_response");
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private Map<String, String> parseCallback(byte[] rawBody) throws CharacterCodingException {
        // 字节上限已在更上游强制执行：PaymentAdapter.CallbackEnvelope 会在其构造器中拒绝
        // 超过 PaymentAdapter.MAX_CALLBACK_BYTES（1 MiB）的请求体，早于任何
        // 适配器看到它。这里原先缺少的是第二道上限 —— 参数个数 —— 而现在
        // 它已在扫描过程中、在解码任何键值对之前生效。
        String body = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBody))
                .toString();
        if (body.isBlank()) {
            throw invalidCallback();
        }
        // 先有界扫描字符集声明，再按声明解码；同时保留 GBK 回调兼容和参数数量限制。
        String charsetName = null;
        int scanStart = 0;
        int scannedPairs = 0;
        while (scanStart <= body.length()) {
            int ampersand = body.indexOf('&', scanStart);
            int end = ampersand < 0 ? body.length() : ampersand;
            if (++scannedPairs > MAX_CALLBACK_PARAMETERS) throw invalidCallback();
            int separator = body.indexOf('=', scanStart);
            if (separator <= scanStart || separator >= end) throw invalidCallback();
            if ("charset".equals(URLDecoder.decode(
                    body.substring(scanStart, separator), StandardCharsets.UTF_8))) {
                if (charsetName != null) throw invalidCallback();
                charsetName = URLDecoder.decode(body.substring(separator + 1, end), StandardCharsets.UTF_8);
            }
            if (ampersand < 0) break;
            scanStart = ampersand + 1;
        }
        Charset charset = callbackCharset(charsetName);
        Map<String, String> parameters = new LinkedHashMap<>();
        int start = 0;
        int pairs = 0;
        while (start <= body.length()) {
            int ampersand = body.indexOf('&', start);
            int end = ampersand < 0 ? body.length() : ampersand;
            pairs++;
            if (pairs > MAX_CALLBACK_PARAMETERS) {
                throw invalidCallback();
            }
            putCallbackParameter(parameters, body, start, end, charset);
            if (ampersand < 0) {
                return parameters;
            }
            start = ampersand + 1;
        }
        return parameters;
    }

    private Charset callbackCharset(String name) {
        if ("UTF-8".equalsIgnoreCase(name)) return StandardCharsets.UTF_8;
        if ("GBK".equalsIgnoreCase(name)) return Charset.forName("GBK");
        throw invalidCallback();
    }

    /**
     * 解码占据 {@code [start, end)} 的那单个键值对，并且只要出现原先逐对检查
     * 所拒绝的任何情况，就让整个回调失败。
     */
    private void putCallbackParameter(
            Map<String, String> parameters, String body, int start, int end, Charset charset) {
        int separator = start;
        while (separator < end && body.charAt(separator) != '=') {
            separator++;
        }
        if (separator <= start || separator >= end) {
            throw invalidCallback();
        }
        String name = URLDecoder.decode(body.substring(start, separator), charset);
        String value = URLDecoder.decode(body.substring(separator + 1, end), charset);
        if (name.isBlank() || name.length() > 128 || value.length() > 8192
                || parameters.putIfAbsent(name, value) != null) {
            throw invalidCallback();
        }
    }

    private String callbackSignatureContent(Map<String, String> parameters) {
        Map<String, String> signed = new TreeMap<>(parameters);
        signed.remove("sign");
        signed.remove("sign_type");
        return signed.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private PaymentCreation failedCreation(
            Instant expiresAt, AlipayGateway.GatewayException exception) {
        return new PaymentCreation(
                outcome(exception), null, exception.kind().name(),
                new PaymentAction(PaymentActionType.NONE, null, Map.of(), null, expiresAt));
    }

    private PaymentCreation uncertainCreation(Instant expiresAt) {
        return new PaymentCreation(
                ProviderOutcome.UNCERTAIN, null, "UNKNOWN",
                new PaymentAction(PaymentActionType.NONE, null, Map.of(), null, expiresAt));
    }

    private ProviderPayment failedPayment(
            String transactionNo, AlipayGateway.GatewayException exception) {
        return unresolvedPayment(
                outcome(exception), transactionNo, exception.kind().name(),
                exception.providerCode());
    }

    private ProviderRefund failedRefund(
            String refundNo, long amount, AlipayGateway.GatewayException exception) {
        return new ProviderRefund(
                outcome(exception), refundNo, null, amount,
                exception.kind().name(), exception.providerCode());
    }

    private ProviderOutcome outcome(AlipayGateway.GatewayException exception) {
        return switch (exception.kind()) {
            case REJECTED -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case TIMEOUT, INTERNAL -> ProviderOutcome.UNCERTAIN;
        };
    }

    private ProviderOutcome paymentOutcome(AlipayGateway.PaymentStatus status) {
        return switch (status) {
            case SUCCESS -> ProviderOutcome.SUCCEEDED;
            case WAIT_BUYER_PAY -> ProviderOutcome.PENDING;
            case CLOSED -> ProviderOutcome.CLOSED;
            case FAILED -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case UNKNOWN -> ProviderOutcome.UNCERTAIN;
        };
    }

    private ProviderOutcome refundOutcome(AlipayGateway.RefundStatus status) {
        return switch (status) {
            case SUCCESS -> ProviderOutcome.SUCCEEDED;
            case PROCESSING -> ProviderOutcome.PENDING;
            case CLOSED -> ProviderOutcome.CLOSED;
            case FAILED -> ProviderOutcome.FAILED;
            case NOT_FOUND -> ProviderOutcome.NOT_FOUND;
            case UNKNOWN -> ProviderOutcome.UNCERTAIN;
        };
    }

    private ProviderPayment unresolvedPayment(
            ProviderOutcome outcome,
            String transactionNo,
            String providerStatus,
            String providerErrorCode) {
        return new ProviderPayment(
                outcome, transactionNo, null, 1, "CNY", null,
                safeCode(providerStatus), safeCode(providerErrorCode),
                sha256(transactionNo.getBytes(StandardCharsets.UTF_8)));
    }

    private String cents(long amount) {
        return BigDecimal.valueOf(amount, 2).toPlainString();
    }

    private long responseCents(String amount) {
        try {
            long cents = new BigDecimal(amount).movePointRight(2).longValueExact();
            if (cents <= 0) {
                throw new ArithmeticException("non-positive amount");
            }
            return cents;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private long parseCents(String amount) {
        try {
            long cents = new BigDecimal(amount).movePointRight(2).longValueExact();
            if (cents <= 0) {
                throw new ArithmeticException("non-positive amount");
            }
            return cents;
        } catch (Exception exception) {
            throw invalidCallback();
        }
    }

    private Instant parseCallbackTime(String value) {
        try {
            return LocalDateTime.parse(value, ALIPAY_TIME).atZone(ALIPAY_ZONE).toInstant();
        } catch (Exception exception) {
            return null;
        }
    }

    private String callbackIdentifier(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !PaymentAdapter.SAFE_PROVIDER_VALUE.matcher(value).matches()) {
            throw invalidCallback();
        }
        return value;
    }

    private static boolean allowedAction(
            URI action, URI gatewayUrl, boolean allowSdkQuery) {
        return action != null
                && "https".equalsIgnoreCase(action.getScheme())
                && action.getHost() != null
                && action.getUserInfo() == null
                && action.getFragment() == null
                && action.getHost().equalsIgnoreCase(gatewayUrl.getHost())
                && effectivePort(action) == effectivePort(gatewayUrl)
                && Objects.equals(normalizedPath(action), normalizedPath(gatewayUrl))
                && (allowSdkQuery || Objects.equals(
                        action.getRawQuery(), gatewayUrl.getRawQuery()));
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static String normalizedPath(URI uri) {
        return uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
    }

    private String safeCode(String code) {
        return code != null && PaymentAdapter.SAFE_PROVIDER_VALUE.matcher(code).matches()
                ? code : "UNKNOWN";
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalidResponse();
        }
        return value;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("payment_hash_failed");
        }
    }

    private IllegalStateException invalidResponse() {
        return new IllegalStateException("invalid_alipay_payment_response");
    }

    private ResponseStatusException invalidCallback() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_alipay_callback");
    }

    @FunctionalInterface
    public interface PaymentExpectationLookup {
        ExpectedPayment require(String transactionNo);
    }

    public record ExpectedPayment(long amount, String currency) {
        public ExpectedPayment {
            if (amount <= 0 || !"CNY".equals(currency)) {
                throw new IllegalArgumentException("invalid payment expectation");
            }
        }
    }

    private record ParsedForm(URI action, Map<String, String> fields) {
    }

    private static final class FormParser extends HTMLEditorKit.ParserCallback {
        private final boolean allowSdkSubmit;
        private final URI gatewayUrl;
        private int formCount;
        private boolean insideForm;
        private boolean invalid;
        private boolean sdkSubmitSeen;
        private boolean sdkScriptStarted;
        private boolean sdkScriptEnded;
        private URI action;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final StringBuilder sdkScript = new StringBuilder();

        private FormParser(boolean allowSdkSubmit, URI gatewayUrl) {
            this.allowSdkSubmit = allowSdkSubmit;
            this.gatewayUrl = Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (tag == HTML.Tag.FORM) {
                startForm(attributes);
            } else if (tag == HTML.Tag.SCRIPT && allowSdkSubmit && formCount == 1
                    && !insideForm && sdkSubmitSeen && !sdkScriptStarted
                    && attributesExactly(attributes, Set.of())) {
                sdkScriptStarted = true;
            } else if (!implicitContainer(tag, attributes)) {
                invalid = true;
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (tag == HTML.Tag.INPUT) {
                input(attributes);
            } else if (!implicitContainer(tag, attributes)) {
                invalid = true;
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (tag == HTML.Tag.FORM) {
                if (!insideForm) {
                    invalid = true;
                }
                insideForm = false;
            } else if (tag == HTML.Tag.SCRIPT && sdkScriptStarted && !sdkScriptEnded) {
                sdkScriptEnded = sdkScript.toString().strip()
                        .equals("document.forms[0].submit();");
                if (!sdkScriptEnded) {
                    invalid = true;
                }
            } else if (tag != HTML.Tag.HTML && tag != HTML.Tag.HEAD && tag != HTML.Tag.BODY) {
                invalid = true;
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (sdkScriptStarted && !sdkScriptEnded) {
                sdkScript.append(data);
                return;
            }
            if (!new String(data).isBlank()) {
                invalid = true;
            }
        }

        @Override
        public void handleComment(char[] data, int position) {
            if (sdkScriptStarted && !sdkScriptEnded) {
                sdkScript.append(data);
            } else {
                invalid = true;
            }
        }

        private void startForm(MutableAttributeSet attributes) {
            formCount++;
            if (insideForm || formCount != 1
                    || !"post".equalsIgnoreCase(attribute(attributes, HTML.Attribute.METHOD))
                    || allowSdkSubmit && !"punchout_form".equals(
                            attribute(attributes, HTML.Attribute.NAME))
                    || !attributesExactly(attributes,
                            allowSdkSubmit ? SDK_FORM_ATTRIBUTES : SANITIZED_FORM_ATTRIBUTES)) {
                invalid = true;
                return;
            }
            try {
                URI candidate = URI.create(attribute(attributes, HTML.Attribute.ACTION));
                if (!allowedAction(candidate, gatewayUrl, allowSdkSubmit)) {
                    invalid = true;
                    return;
                }
                action = candidate;
                insideForm = true;
            } catch (Exception exception) {
                invalid = true;
            }
        }

        private void input(MutableAttributeSet attributes) {
            String type = attribute(attributes, HTML.Attribute.TYPE);
            String name = attribute(attributes, HTML.Attribute.NAME);
            String value = attribute(attributes, HTML.Attribute.VALUE);
            if (sdkSubmitSeen) {
                invalid = true;
                return;
            }
            if (allowSdkSubmit && "submit".equalsIgnoreCase(type)
                    && insideForm && !sdkSubmitSeen && name == null
                    && "\u7acb\u5373\u652f\u4ed8".equals(value)
                    && "display:none".equalsIgnoreCase(
                            attribute(attributes, HTML.Attribute.STYLE))
                    && attributesExactly(attributes, SDK_SUBMIT_ATTRIBUTES)) {
                sdkSubmitSeen = true;
                return;
            }
            if (!insideForm || !"hidden".equalsIgnoreCase(type)
                    || name == null || value == null
                    || !attributesExactly(attributes, HIDDEN_INPUT_ATTRIBUTES)
                    || !PaymentAdapter.HTML_FORM_FIELDS.contains(name)
                    || value.isBlank() || value.length() > 4096
                    || fields.putIfAbsent(name, value) != null) {
                invalid = true;
            }
        }

        private boolean implicitContainer(HTML.Tag tag, MutableAttributeSet attributes) {
            return (tag == HTML.Tag.HTML || tag == HTML.Tag.HEAD || tag == HTML.Tag.BODY)
                    && attributes.isDefined(IMPLIED);
        }

        private String attribute(MutableAttributeSet attributes, HTML.Attribute name) {
            Object value = attributes.getAttribute(name);
            return value == null ? null : value.toString();
        }

        private boolean attributesExactly(
                MutableAttributeSet attributes, Set<String> expected) {
            Set<String> actual = new java.util.HashSet<>();
            var names = attributes.getAttributeNames();
            while (names.hasMoreElements()) {
                actual.add(names.nextElement().toString().toLowerCase(java.util.Locale.ROOT));
            }
            return actual.equals(expected);
        }

        private ParsedForm result() {
            if (invalid || insideForm || formCount != 1 || action == null || fields.isEmpty()
                    || allowSdkSubmit && (!sdkSubmitSeen || !sdkScriptEnded)) {
                throw new IllegalStateException("invalid_alipay_payment_response");
            }
            return new ParsedForm(action, Map.copyOf(fields));
        }
    }
}
