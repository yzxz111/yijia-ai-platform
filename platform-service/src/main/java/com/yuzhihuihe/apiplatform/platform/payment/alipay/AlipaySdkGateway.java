package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipInputStream;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.AlipayResponse;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayDataDataserviceBillDownloadurlQueryModel;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.alipay.api.internal.util.AlipayLogger;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class AlipaySdkGateway implements AlipayGateway, AutoCloseable {

    private static final int MAX_BILL_BYTES = 50 * 1024 * 1024;
    private static final Set<String> NOT_FOUND_CODES = Set.of(
            "ACQ.TRADE_NOT_EXIST",
            "ACQ.REFUND_NOT_EXIST",
            "TRADE_NOT_EXIST",
            "REFUND_NOT_EXIST");
    private static final Set<String> DETERMINISTIC_REJECTION_CODES = Set.of(
            "ACQ.TRADE_HAS_CLOSE",
            "ACQ.TRADE_HAS_FINISHED",
            "ACQ.BUYER_BALANCE_NOT_ENOUGH",
            "ACQ.BUYER_PAYMENT_AMOUNT_DAY_LIMIT_ERROR",
            "ACQ.BUYER_PAYMENT_AMOUNT_MONTH_LIMIT_ERROR",
            "ACQ.BUYER_SELLER_EQUAL",
            "ACQ.CURRENCY_NOT_SUPPORT",
            "ACQ.REASON_TRADE_REFUND_FEE_ERR",
            "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL",
            "ACQ.REFUND_FEE_ERROR",
            "ACQ.TRADE_NOT_ALLOW_REFUND");
    private static final DateTimeFormatter ALIPAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final AlipayClient client;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration totalOperationTimeout;
    private final URI gatewayUrl;
    private final String appId;
    private final String sellerId;
    private final int maxBillBytes;

    public AlipaySdkGateway(
            URI gatewayUrl,
            String appId,
            String sellerId,
            PrivateKey applicationPrivateKey,
            PublicKey alipayPublicKey,
            String charset,
            String signType,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout) {
        this(gatewayUrl, appId, sellerId, applicationPrivateKey, alipayPublicKey,
                charset, signType, connectTimeout, readTimeout, totalOperationTimeout,
                MAX_BILL_BYTES);
    }

    public AlipaySdkGateway(
            URI gatewayUrl,
            String appId,
            String sellerId,
            PrivateKey applicationPrivateKey,
            PublicKey alipayPublicKey,
            String charset,
            String signType,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout,
            int maxBillBytes) {
        this.appId = Objects.requireNonNull(appId, "appId");
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.gatewayUrl = Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        this.totalOperationTimeout = Objects.requireNonNull(
                totalOperationTimeout, "totalOperationTimeout");
        this.maxBillBytes = boundedBillBytes(maxBillBytes);
        Objects.requireNonNull(readTimeout, "readTimeout");
        byte[] privateKeyBytes = Objects.requireNonNull(
                applicationPrivateKey, "applicationPrivateKey").getEncoded();
        try {
            AlipayConfig config = new AlipayConfig();
            config.setServerUrl(this.gatewayUrl.toString());
            config.setAppId(appId);
            config.setPrivateKey(Base64.getEncoder().encodeToString(privateKeyBytes));
            config.setFormat("json");
            config.setCharset(Objects.requireNonNull(charset, "charset"));
            config.setAlipayPublicKey(Base64.getEncoder().encodeToString(
                    Objects.requireNonNull(alipayPublicKey, "alipayPublicKey").getEncoded()));
            config.setSignType(Objects.requireNonNull(signType, "signType"));
            config.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
            config.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
            AlipayLogger.setNeedEnableLogger(false);
            this.client = new DefaultAlipayClient(config);
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("alipay_client_initialization_failed");
        } finally {
            Arrays.fill(privateKeyBytes, (byte) 0);
        }
    }

    @Override
    public String createPagePayment(PaymentRequest request) {
        return call(() -> {
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            model.setOutTradeNo(request.merchantOrderNo());
            model.setProductCode("FAST_INSTANT_TRADE_PAY");
            model.setTotalAmount(request.totalAmount());
            model.setSubject(request.subject());
            model.setTimeExpire(format(request.expiresAt()));
            AlipayTradePagePayRequest sdkRequest = new AlipayTradePagePayRequest();
            sdkRequest.setBizModel(model);
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            sdkRequest.setReturnUrl(request.returnUrl().toString());
            AlipayTradePagePayResponse response = client.pageExecute(sdkRequest, "POST");
            requireSuccessful(response);
            String html = response.getBody() == null
                    ? response.getPageRedirectionData() : response.getBody();
            return AlipayAdapter.normalizeSdkForm(html, gatewayUrl);
        });
    }

    @Override
    public String createWapPayment(PaymentRequest request) {
        return call(() -> {
            AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
            model.setOutTradeNo(request.merchantOrderNo());
            model.setProductCode("QUICK_WAP_WAY");
            model.setTotalAmount(request.totalAmount());
            model.setSubject(request.subject());
            model.setSellerId(request.sellerId());
            model.setQuitUrl(request.returnUrl().toString());
            model.setTimeExpire(format(request.expiresAt()));
            AlipayTradeWapPayRequest sdkRequest = new AlipayTradeWapPayRequest();
            sdkRequest.setBizModel(model);
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            sdkRequest.setReturnUrl(request.returnUrl().toString());
            AlipayTradeWapPayResponse response = client.pageExecute(sdkRequest, "POST");
            requireSuccessful(response);
            String html = response.getBody() == null
                    ? response.getPageRedirectionData() : response.getBody();
            return AlipayAdapter.normalizeSdkForm(html, gatewayUrl);
        });
    }

    @Override
    public PaymentResult queryPayment(String merchantOrderNo) {
        return call(() -> query(merchantOrderNo));
    }

    @Override
    public PaymentResult closePayment(String merchantOrderNo) {
        return call(() -> {
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(merchantOrderNo);
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            request.setBizModel(model);
            AlipayTradeCloseResponse response = client.execute(request);
            if (response != null && !response.isSuccess()
                    && "ACQ.TRADE_HAS_FINISHED".equalsIgnoreCase(
                            firstNonBlank(response.getSubCode(), response.getCode()))) {
                return query(merchantOrderNo);
            }
            requireSuccessful(response);
            if (!merchantOrderNo.equals(response.getOutTradeNo())) {
                throw invalidResponse();
            }
            return query(merchantOrderNo);
        });
    }

    @Override
    public RefundResult createRefund(RefundRequest request) {
        return call(() -> {
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(request.merchantOrderNo());
            model.setTradeNo(request.tradeNo());
            model.setOutRequestNo(request.merchantRefundNo());
            model.setRefundAmount(request.refundAmount());
            model.setRefundCurrency(request.currency());
            model.setRefundReason(request.reason());
            AlipayTradeRefundRequest sdkRequest = new AlipayTradeRefundRequest();
            sdkRequest.setBizModel(model);
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            AlipayTradeRefundResponse response = client.execute(sdkRequest);
            requireSuccessful(response);
            if (!request.merchantOrderNo().equals(response.getOutTradeNo())
                    || !request.tradeNo().equals(response.getTradeNo())
                    || !request.refundAmount().equals(response.getRefundFee())) {
                throw invalidResponse();
            }
            return new RefundResult(
                    RefundStatus.SUCCESS, request.appId(), request.sellerId(),
                    request.merchantOrderNo(), request.merchantRefundNo(), response.getTradeNo(),
                    response.getRefundFee(), request.currency(), response.getCode());
        });
    }

    @Override
    public RefundResult queryRefund(RefundQueryRequest query) {
        Objects.requireNonNull(query, "query");
        return call(() -> {
            if (!appId.equals(query.appId()) || !sellerId.equals(query.sellerId())) {
                throw invalidResponse();
            }
            AlipayTradeFastpayRefundQueryModel model =
                    new AlipayTradeFastpayRefundQueryModel();
            model.setOutTradeNo(query.merchantOrderNo());
            model.setTradeNo(query.tradeNo());
            model.setOutRequestNo(query.merchantRefundNo());
            AlipayTradeFastpayRefundQueryRequest request =
                    new AlipayTradeFastpayRefundQueryRequest();
            request.setBizModel(model);
            AlipayTradeFastpayRefundQueryResponse response = client.execute(request);
            requireSuccessful(response);
            if (!query.merchantOrderNo().equals(response.getOutTradeNo())
                    || !query.merchantRefundNo().equals(response.getOutRequestNo())
                    || !query.tradeNo().equals(response.getTradeNo())
                    || !query.refundAmount().equals(response.getRefundAmount())) {
                throw invalidResponse();
            }
            return new RefundResult(
                    refundStatus(response.getRefundStatus()), appId, sellerId,
                    response.getOutTradeNo(), response.getOutRequestNo(), response.getTradeNo(),
                    response.getRefundAmount(), query.currency(), response.getCode());
        });
    }

    @Override
    public BillResult downloadBill(LocalDate billDate) {
        return call(() -> {
            AlipayDataDataserviceBillDownloadurlQueryModel model =
                    new AlipayDataDataserviceBillDownloadurlQueryModel();
            model.setBillType("trade");
            model.setBillDate(billDate.toString());
            AlipayDataDataserviceBillDownloadurlQueryRequest request =
                    new AlipayDataDataserviceBillDownloadurlQueryRequest();
            request.setBizModel(model);
            AlipayDataDataserviceBillDownloadurlQueryResponse response = client.execute(request);
            requireSuccessful(response);
            byte[] bytes = download(response.getBillDownloadUrl());
            return new BillResult(
                    billDate, appId, sellerId(), sha256(bytes),
                    parseBill(bytes, maxBillBytes));
        });
    }

    static List<BillLine> parseBill(byte[] archive, int maxBytes) {
        Objects.requireNonNull(archive, "archive");
        boundedBillBytes(maxBytes);
        List<BillLine> lines = new ArrayList<>();
        int expandedBytes = 0;
        boolean detailFound = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            int entryCount = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entryCount > 100) {
                    throw rejected("BILL_INVALID_FORMAT");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                int remaining = maxBytes - expandedBytes;
                if (remaining <= 0) {
                    throw rejected("BILL_TOO_LARGE");
                }
                byte[] content = readBounded(zip, remaining);
                expandedBytes = Math.addExact(expandedBytes, content.length);
                List<BillLine> parsed = parseDetail(content);
                if (parsed == null) {
                    continue;
                }
                detailFound = true;
                lines.addAll(parsed);
                if (lines.size() > PaymentAdapter.MAX_BILL_ENTRIES) {
                    throw rejected("BILL_RECORD_LIMIT_EXCEEDED");
                }
            }
        } catch (GatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        if (!detailFound) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        return List.copyOf(lines);
    }

    private static List<BillLine> parseDetail(byte[] content) {
        String text = decodeBill(content);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setCommentMarker('#')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            Map<String, Integer> columns = columns(parser);
            if (!columns.containsKey("支付宝交易号") || !columns.containsKey("业务类型")) {
                return null;
            }
            int providerRecordId = requiredColumn(columns, "支付宝交易号");
            int transactionNo = requiredColumn(columns, "商户订单号");
            int businessType = requiredColumn(columns, "业务类型");
            int occurredAt = requiredColumn(columns, "完成时间");
            int amount = requiredColumn(columns, "订单金额（元）", "订单金额(元)");
            int refundNo = requiredColumn(columns, "退款批次号/请求号");
            int fee = optionalColumn(columns, "服务费（元）", "服务费(元)");
            List<BillLine> lines = new ArrayList<>();
            for (CSVRecord record : parser) {
                String type = requiredValue(record, businessType);
                String providerId = requiredValue(record, providerRecordId);
                String merchantOrder = requiredValue(record, transactionNo);
                String normalizedAmount = amount(requiredValue(record, amount));
                String rawFee = optionalValue(record, fee);
                boolean feeKnown = rawFee != null;
                String normalizedFee = nonNegativeAmount(feeKnown ? rawFee : "0");
                Instant time = instant(requiredValue(record, occurredAt));
                if (type.contains("退款")) {
                    String merchantRefund = requiredValue(record, refundNo);
                    lines.add(new BillLine(
                            merchantRefund, BillEntryType.REFUND, merchantOrder,
                            merchantRefund, normalizedAmount, "CNY", normalizedFee, "CNY", feeKnown,
                            PaymentStatus.SUCCESS, time));
                } else if (type.contains("交易")) {
                    lines.add(new BillLine(
                            providerId, BillEntryType.PAYMENT, merchantOrder,
                            null, normalizedAmount, "CNY", normalizedFee, "CNY", feeKnown,
                            PaymentStatus.SUCCESS, time));
                } else {
                    throw rejected("BILL_INVALID_FORMAT");
                }
            }
            return List.copyOf(lines);
        } catch (GatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static String decodeBill(byte[] content) {
        try {
            return decode(content, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return decode(content, Charset.forName("GB18030"));
        }
    }

    private static String decode(byte[] content, Charset charset) {
        try {
            String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (Exception exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static Map<String, Integer> columns(CSVParser parser) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<String> names = parser.getHeaderNames();
        for (int index = 0; index < names.size(); index++) {
            String name = clean(names.get(index));
            if (name == null || result.putIfAbsent(name, index) != null) {
                throw rejected("BILL_INVALID_FORMAT");
            }
        }
        return result;
    }

    private static int requiredColumn(Map<String, Integer> columns, String... names) {
        int column = optionalColumn(columns, names);
        if (column >= 0) {
            return column;
        }
        throw rejected("BILL_INVALID_FORMAT");
    }

    private static int optionalColumn(Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    private static String optionalValue(CSVRecord record, int column) {
        if (column < 0) {
            return null;
        }
        String value = clean(record.get(column));
        return value;
    }

    private static String requiredValue(CSVRecord record, int column) {
        if (column < 0 || column >= record.size()) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        String value = clean(record.get(column));
        if (value == null) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        return value;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1).strip();
        }
        if (normalized.startsWith("`")) {
            normalized = normalized.substring(1).strip();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String amount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value).abs().setScale(2);
            if (amount.signum() <= 0) {
                throw new ArithmeticException("non-positive amount");
            }
            return amount.toPlainString();
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static String nonNegativeAmount(String value) {
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2);
            if (amount.signum() < 0) {
                throw new ArithmeticException("negative amount");
            }
            return amount.toPlainString();
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static Instant instant(String value) {
        try {
            return LocalDateTime.parse(value, ALIPAY_TIME).atZone(ALIPAY_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
            if (read == 0) {
                continue;
            }
            total = Math.addExact(total, read);
            if (total > maxBytes) {
                throw rejected("BILL_TOO_LARGE");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static int boundedBillBytes(int value) {
        if (value <= 0 || value >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("max bill bytes must be positive and bounded");
        }
        return value;
    }

    private static GatewayException rejected(String code) {
        return new GatewayException(FailureKind.REJECTED, code);
    }

    private PaymentResult query(String merchantOrderNo) throws AlipayApiException {
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(merchantOrderNo);
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizModel(model);
        AlipayTradeQueryResponse response = client.execute(request);
        requireSuccessful(response);
        if (!merchantOrderNo.equals(response.getOutTradeNo())) {
            throw invalidResponse();
        }
        String currency = firstNonBlank(response.getPayCurrency(), "CNY");
        return new PaymentResult(
                paymentStatus(response.getTradeStatus()), appId, sellerId(),
                response.getOutTradeNo(), response.getTradeNo(), response.getTotalAmount(),
                currency, instant(response.getSendPayDate()), response.getCode());
    }

    private byte[] download(String value) throws Exception {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalidResponse();
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(totalOperationTimeout)
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new GatewayException(
                    classifyBillHttpFailure(response.statusCode()),
                    "BILL_DOWNLOAD_FAILED");
        }
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maxBillBytes + 1);
            if (bytes.length > maxBillBytes) {
                throw new GatewayException(FailureKind.REJECTED, "BILL_TOO_LARGE");
            }
            return bytes;
        }
    }

    private <T> T call(CheckedSupplier<T> action) {
        Future<T> future = executor.submit(action::get);
        try {
            return future.get(totalOperationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new GatewayException(FailureKind.TIMEOUT, "TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayException(FailureKind.INTERNAL, "INTERRUPTED");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof GatewayException gatewayException) {
                throw gatewayException;
            }
            if (cause instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            if (cause instanceof AlipayApiException alipayException) {
                throw apiFailure(alipayException);
            }
            if (isTimeout(cause)) {
                throw new GatewayException(FailureKind.TIMEOUT, "TIMEOUT");
            }
            throw new GatewayException(FailureKind.INTERNAL, "INTERNAL");
        }
    }

    private GatewayException apiFailure(AlipayApiException exception) {
        String code = safeCode(exception.getErrCode());
        return new GatewayException(
                isTimeout(exception) ? FailureKind.TIMEOUT : FailureKind.INTERNAL, code);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireSuccessful(AlipayResponse response) {
        if (response == null) {
            throw invalidResponse();
        }
        if (!response.isSuccess()) {
            String code = safeCode(firstNonBlank(response.getSubCode(), response.getCode()));
            throw new GatewayException(classifyProviderFailure(code), code);
        }
    }

    static FailureKind classifyProviderFailure(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (NOT_FOUND_CODES.contains(normalized)) {
            return FailureKind.NOT_FOUND;
        }
        if (DETERMINISTIC_REJECTION_CODES.contains(normalized)) {
            return FailureKind.REJECTED;
        }
        return FailureKind.INTERNAL;
    }

    static FailureKind classifyBillHttpFailure(int statusCode) {
        if (statusCode == 408 || statusCode == 504) {
            return FailureKind.TIMEOUT;
        }
        if (statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            return FailureKind.INTERNAL;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return FailureKind.REJECTED;
        }
        return FailureKind.INTERNAL;
    }

    private PaymentStatus paymentStatus(String value) {
        return switch (value == null ? "" : value) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PaymentStatus.SUCCESS;
            case "WAIT_BUYER_PAY" -> PaymentStatus.WAIT_BUYER_PAY;
            case "TRADE_CLOSED" -> PaymentStatus.CLOSED;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    private RefundStatus refundStatus(String value) {
        return switch (value == null ? "" : value) {
            case "REFUND_SUCCESS", "SUCCESS" -> RefundStatus.SUCCESS;
            case "REFUND_PROCESSING", "PROCESSING" -> RefundStatus.PROCESSING;
            case "REFUND_CLOSED", "CLOSED" -> RefundStatus.CLOSED;
            default -> RefundStatus.UNKNOWN;
        };
    }

    private String format(Instant instant) {
        return ALIPAY_TIME.format(instant.atZone(ALIPAY_ZONE));
    }

    private Instant instant(java.util.Date value) {
        return value == null ? null : value.toInstant();
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private String sellerId() {
        return sellerId;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String safeCode(String code) {
        return code != null && SAFE_CODE.matcher(code).matches() ? code : "UNKNOWN";
    }

    private IllegalStateException invalidResponse() {
        return new IllegalStateException("invalid_alipay_payment_response");
    }

    @Override
    public void close() {
        executor.close();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

}
