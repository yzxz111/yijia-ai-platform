package com.yuzhihuihe.apiplatform.platform.payment.wechat;

import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.HttpException;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.cipher.RSAPrivacyDecryptor;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.payments.h5.H5Service;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.yuzhihuihe.apiplatform.platform.payment.BillEntryType;
import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class WechatPaySdkGateway implements WechatPayGateway {

    private static final int MAX_BILL_BYTES = 50 * 1024 * 1024;
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BILL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NativePayService nativePayService;
    private final H5Service h5Service;
    private final RefundService refundService;
    private final BillDownloadServiceExtension billService;
    private final String merchantId;
    private final Duration totalOperationTimeout;
    private final int maxBillBytes;

    public WechatPaySdkGateway(
            String merchantId,
            PrivateKey merchantPrivateKey,
            String merchantCertificateSerial,
            String apiV3Key,
            PublicKey platformPublicKey,
            String platformPublicKeyId,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout) {
        this(merchantId, merchantPrivateKey, merchantCertificateSerial, apiV3Key,
                platformPublicKey, platformPublicKeyId, connectTimeout, readTimeout,
                totalOperationTimeout, MAX_BILL_BYTES);
    }

    public WechatPaySdkGateway(
            String merchantId,
            PrivateKey merchantPrivateKey,
            String merchantCertificateSerial,
            String apiV3Key,
            PublicKey platformPublicKey,
            String platformPublicKeyId,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalOperationTimeout,
            int maxBillBytes) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.totalOperationTimeout = Objects.requireNonNull(
                totalOperationTimeout, "totalOperationTimeout");
        this.maxBillBytes = boundedBillBytes(maxBillBytes);
        Config config = new RSAPublicKeyConfig.Builder()
                .merchantId(merchantId)
                .privateKey(Objects.requireNonNull(merchantPrivateKey, "merchantPrivateKey"))
                .merchantSerialNumber(merchantCertificateSerial)
                .apiV3Key(apiV3Key)
                .publicKey(Objects.requireNonNull(platformPublicKey, "platformPublicKey"))
                .publicKeyId(platformPublicKeyId)
                .build();
        HttpClient httpClient = new DefaultHttpClientBuilder()
                .config(config)
                .connectTimeoutMs(Math.toIntExact(connectTimeout.toMillis()))
                .readTimeoutMs(Math.toIntExact(readTimeout.toMillis()))
                .writeTimeoutMs(Math.toIntExact(readTimeout.toMillis()))
                .build();
        nativePayService = new NativePayService.Builder().httpClient(httpClient).build();
        h5Service = new H5Service.Builder().httpClient(httpClient).build();
        refundService = new RefundService.Builder().httpClient(httpClient).build();
        billService = new BillDownloadServiceExtension.Builder()
                .httpClient(httpClient)
                .decryptor(new RSAPrivacyDecryptor(merchantPrivateKey))
                .build();
    }

    @Override
    public NativePrepay prepayNative(PrepayCommand request) {
        return execute(() -> {
            com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest sdkRequest =
                    new com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest();
            sdkRequest.setAppid(request.appId());
            sdkRequest.setMchid(request.merchantId());
            sdkRequest.setDescription(request.description());
            sdkRequest.setOutTradeNo(request.merchantOrderNo());
            sdkRequest.setTimeExpire(format(request.expiresAt()));
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            com.wechat.pay.java.service.payments.nativepay.model.Amount amount =
                    new com.wechat.pay.java.service.payments.nativepay.model.Amount();
            amount.setTotal(Math.toIntExact(request.amount()));
            amount.setCurrency(request.currency());
            sdkRequest.setAmount(amount);
            var response = nativePayService.prepay(sdkRequest);
            return new NativePrepay("PREPAY_SUCCESS", response.getCodeUrl());
        });
    }

    @Override
    public H5Prepay prepayH5(PrepayCommand request) {
        return execute(() -> {
            com.wechat.pay.java.service.payments.h5.model.PrepayRequest sdkRequest =
                    new com.wechat.pay.java.service.payments.h5.model.PrepayRequest();
            sdkRequest.setAppid(request.appId());
            sdkRequest.setMchid(request.merchantId());
            sdkRequest.setDescription(request.description());
            sdkRequest.setOutTradeNo(request.merchantOrderNo());
            sdkRequest.setTimeExpire(format(request.expiresAt()));
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            com.wechat.pay.java.service.payments.h5.model.Amount amount =
                    new com.wechat.pay.java.service.payments.h5.model.Amount();
            amount.setTotal(Math.toIntExact(request.amount()));
            amount.setCurrency(request.currency());
            sdkRequest.setAmount(amount);
            com.wechat.pay.java.service.payments.h5.model.SceneInfo scene =
                    new com.wechat.pay.java.service.payments.h5.model.SceneInfo();
            scene.setPayerClientIp(request.clientIp());
            com.wechat.pay.java.service.payments.h5.model.H5Info h5Info =
                    new com.wechat.pay.java.service.payments.h5.model.H5Info();
            h5Info.setType("Wap");
            h5Info.setAppName("一加AI中台");
            h5Info.setAppUrl(origin(request.notifyUrl()));
            scene.setH5Info(h5Info);
            sdkRequest.setSceneInfo(scene);
            var response = h5Service.prepay(sdkRequest);
            return new H5Prepay("PREPAY_SUCCESS", URI.create(response.getH5Url()));
        });
    }

    @Override
    public PaymentResult queryPayment(String merchantOrderNo) {
        return execute(() -> {
            var request = new com.wechat.pay.java.service.payments.nativepay.model
                    .QueryOrderByOutTradeNoRequest();
            request.setMchid(merchantId);
            request.setOutTradeNo(merchantOrderNo);
            return payment(nativePayService.queryOrderByOutTradeNo(request));
        });
    }

    @Override
    public PaymentResult closePayment(String merchantOrderNo) {
        return execute(() -> {
            var request = new com.wechat.pay.java.service.payments.nativepay.model
                    .CloseOrderRequest();
            request.setMchid(merchantId);
            request.setOutTradeNo(merchantOrderNo);
            nativePayService.closeOrder(request);
            return new PaymentResult(
                    PaymentStatus.CLOSED, null, merchantId, merchantOrderNo, null,
                    1, "CNY", null, "CLOSED");
        });
    }

    @Override
    public RefundResult createRefund(RefundRequest request) {
        return execute(() -> {
            CreateRequest sdkRequest = new CreateRequest();
            sdkRequest.setOutTradeNo(request.merchantOrderNo());
            sdkRequest.setTransactionId(request.transactionId());
            sdkRequest.setOutRefundNo(request.merchantRefundNo());
            sdkRequest.setReason(request.reason());
            sdkRequest.setNotifyUrl(request.notifyUrl().toString());
            AmountReq amount = new AmountReq();
            amount.setRefund(request.amount());
            amount.setTotal(request.totalAmount());
            amount.setCurrency(request.currency());
            sdkRequest.setAmount(amount);
            return refund(refundService.create(sdkRequest));
        });
    }

    @Override
    public RefundResult queryRefund(String merchantRefundNo) {
        return execute(() -> {
            QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
            request.setOutRefundNo(merchantRefundNo);
            return refund(refundService.queryByOutRefundNo(request));
        });
    }

    @Override
    public BillResult downloadBill(LocalDate billDate) {
        return execute(() -> {
            GetTradeBillRequest request = new GetTradeBillRequest();
            request.setBillDate(billDate.toString());
            DigestBillEntity entity = billService.getTradeBill(request);
            byte[] content;
            try (InputStream input = entity.getInputStream()) {
                content = readBounded(input, maxBillBytes);
                if (!entity.verifyHash()) {
                    throw new GatewayException(FailureKind.REJECTED, "BILL_HASH_MISMATCH");
                }
            }
            return new BillResult(billDate, sha256(content), parseBill(content));
        });
    }

    static List<BillLine> parseBill(byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .get();
            try (CSVParser parser = format.parse(new StringReader(text))) {
                Map<String, Integer> columns = columns(parser);
                int occurredAt = requiredColumn(columns, "交易时间");
                int providerPaymentId = requiredColumn(columns, "微信订单号");
                int transactionNo = requiredColumn(columns, "商户订单号");
                int paymentStatus = requiredColumn(columns, "交易状态");
                int currency = requiredColumn(columns, "货币种类");
                int paymentAmount = requiredColumn(
                        columns, "订单金额", "总金额", "应结订单金额");
                int providerRefundId = requiredColumn(columns, "微信退款单号");
                int refundNo = requiredColumn(columns, "商户退款单号");
                int refundAmount = requiredColumn(columns, "申请退款金额", "退款金额");
                int refundStatus = requiredColumn(columns, "退款状态");
                int fee = optionalColumn(columns, "手续费", "手续费（元）", "手续费(元)");
                List<BillLine> lines = new ArrayList<>();
                for (CSVRecord record : parser) {
                    if (record.size() == 0 || value(record, 0) == null) {
                        continue;
                    }
                    if (value(record, 0).startsWith("总交易单数")) {
                        break;
                    }
                    String refund = value(record, refundNo);
                    Instant time = instant(value(record, occurredAt));
                    String unit = cny(value(record, currency));
                    String rawFee = optionalValue(record, fee);
                    boolean feeKnown = rawFee != null;
                    long feeAmount = nonNegativeCents(feeKnown ? rawFee : "0");
                    if (refund != null) {
                        lines.add(new BillLine(
                                requiredValue(record, providerRefundId), BillEntryType.REFUND,
                                null, refund, cents(requiredValue(record, refundAmount)), unit,
                                feeAmount, unit, feeKnown,
                                refundStatus(value(record, refundStatus)), time));
                    } else {
                        lines.add(new BillLine(
                                requiredValue(record, providerPaymentId), BillEntryType.PAYMENT,
                                requiredValue(record, transactionNo), null,
                                cents(requiredValue(record, paymentAmount)), unit,
                                feeAmount, unit, feeKnown,
                                paymentStatus(value(record, paymentStatus)), time));
                    }
                    if (lines.size() > PaymentAdapter.MAX_BILL_ENTRIES) {
                        throw rejected("BILL_RECORD_LIMIT_EXCEEDED");
                    }
                }
                return List.copyOf(lines);
            }
        } catch (GatewayException exception) {
            throw exception;
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
        String value = value(record, column);
        return value;
    }

    private static String requiredValue(CSVRecord record, int column) {
        String value = value(record, column);
        if (value == null) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        return value;
    }

    private static String value(CSVRecord record, int column) {
        if (column < 0 || column >= record.size()) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        return clean(record.get(column));
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

    private static long cents(String value) {
        try {
            long cents = new BigDecimal(value).abs().movePointRight(2).longValueExact();
            if (cents <= 0) {
                throw new ArithmeticException("non-positive amount");
            }
            return cents;
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static long nonNegativeCents(String value) {
        try {
            long cents = new BigDecimal(value).movePointRight(2).longValueExact();
            if (cents < 0) {
                throw new ArithmeticException("negative amount");
            }
            return cents;
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static String cny(String value) {
        if (!"CNY".equalsIgnoreCase(value) && !"人民币".equals(value)) {
            throw rejected("BILL_INVALID_FORMAT");
        }
        return "CNY";
    }

    private static Instant instant(String value) {
        try {
            return LocalDateTime.parse(value, BILL_TIME).atZone(BILL_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw rejected("BILL_INVALID_FORMAT");
        }
    }

    private static PaymentStatus paymentStatus(String value) {
        try {
            return PaymentStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException exception) {
            return PaymentStatus.UNKNOWN;
        }
    }

    private static PaymentStatus refundStatus(String value) {
        return switch (value == null ? "" : value.toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> PaymentStatus.SUCCESS;
            case "PROCESSING" -> PaymentStatus.USERPAYING;
            case "CLOSED" -> PaymentStatus.CLOSED;
            case "ABNORMAL" -> PaymentStatus.PAYERROR;
            default -> PaymentStatus.UNKNOWN;
        };
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

    static byte[] readBounded(InputStream input, int maxBytes) throws java.io.IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be positive and bounded");
        }
        byte[] content = input.readNBytes(maxBytes + 1);
        if (content.length > maxBytes) {
            throw new GatewayException(FailureKind.REJECTED, "BILL_TOO_LARGE");
        }
        return content;
    }

    private PaymentResult payment(Transaction transaction) {
        PaymentStatus status;
        try {
            status = transaction.getTradeState() == null
                    ? PaymentStatus.UNKNOWN
                    : PaymentStatus.valueOf(transaction.getTradeState().name());
        } catch (RuntimeException exception) {
            status = PaymentStatus.UNKNOWN;
        }
        long amount = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                ? 0 : transaction.getAmount().getTotal();
        String currency = transaction.getAmount() == null
                ? null : transaction.getAmount().getCurrency();
        return new PaymentResult(
                status, transaction.getAppid(), transaction.getMchid(),
                transaction.getOutTradeNo(), transaction.getTransactionId(), amount, currency,
                parseInstant(transaction.getSuccessTime()), status.name());
    }

    private RefundResult refund(Refund refund) {
        RefundStatus status;
        try {
            status = refund.getStatus() == null
                    ? RefundStatus.UNKNOWN : RefundStatus.valueOf(refund.getStatus().name());
        } catch (RuntimeException exception) {
            status = RefundStatus.UNKNOWN;
        }
        long amount = refund.getAmount() == null || refund.getAmount().getRefund() == null
                ? 0 : refund.getAmount().getRefund();
        return new RefundResult(
                status, refund.getOutRefundNo(), refund.getRefundId(), amount, status.name());
    }

    private <T> T execute(Callable<T> operation) {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread worker = Thread.ofVirtual().name("wechat-pay-sdk-operation").start(task);
        try {
            return task.get(totalOperationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new GatewayException(FailureKind.TIMEOUT, "TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new GatewayException(FailureKind.TIMEOUT, "INTERRUPTED");
        } catch (ExecutionException exception) {
            throw translate(exception.getCause());
        } finally {
            if (task.isCancelled()) {
                worker.interrupt();
            }
        }
    }

    private GatewayException translate(Throwable exception) {
        if (exception instanceof GatewayException gatewayException) {
            return gatewayException;
        }
        if (exception instanceof ServiceException serviceException) {
            FailureKind kind = classifyServiceStatus(serviceException.getHttpStatusCode());
            return new GatewayException(kind, serviceException.getErrorCode());
        }
        if (exception instanceof HttpException) {
            return new GatewayException(FailureKind.TIMEOUT, "NETWORK_ERROR");
        }
        return new GatewayException(FailureKind.INTERNAL, "SDK_ERROR");
    }

    static FailureKind classifyServiceStatus(int status) {
        if (status == 404) {
            return FailureKind.NOT_FOUND;
        }
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return FailureKind.INTERNAL;
        }
        return status >= 400 && status < 500
                ? FailureKind.REJECTED : FailureKind.INTERNAL;
    }

    private String format(Instant value) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(ZoneOffset.UTC));
    }

    private String origin(URI value) {
        int port = value.getPort();
        return value.getScheme() + "://" + value.getHost() + (port < 0 ? "" : ":" + port);
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new GatewayException(FailureKind.INTERNAL, "HASH_ERROR");
        }
    }
}
