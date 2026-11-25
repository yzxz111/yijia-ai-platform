package com.yuzhihuihe.apiplatform.platform.payment.alipay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;

import com.yuzhihuihe.apiplatform.platform.payment.PaymentAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 支付宝回调解析器必须先限制它在一个未认证请求体上所做的事，
 * 而且要在解码任何内容之前完成这一限制。
 *
 * <p>该解析器以前会执行 {@code body.split("&", -1)}，并且只在
 * 遍历结果时检查 100 个参数的上限，于是一个由 {@code &} 构成的请求体在第一次
 * 拒绝之前就会为每个分隔符分配一个 {@code String}。限制输入的字节上限在上游已经存在
 * （{@code PaymentAdapter.CallbackEnvelope} 中的 {@code PaymentAdapter.MAX_CALLBACK_BYTES}）；
 * 缺失的是第二道上限。现在扫描会统计键值对，一旦超过
 * 上限就立即拒绝该回调，因此被拒绝的请求体所分配的字节不再包含按分隔符
 * 产生的数组——解码后的请求体是唯一仍然随输入规模增长的部分。
 */
class AlipayCallbackParserBoundTest {

    private static final int MAX_CALLBACK_PARAMETERS = 100;
    private static final int SMALL_BODY_BYTES = 512 * 1024;
    /** {@link PaymentAdapter.CallbackEnvelope} 完全能够接受的最大请求体。 */
    private static final int LARGE_BODY_BYTES = PaymentAdapter.MAX_CALLBACK_BYTES;
    private static final int SAMPLES = 16;
    /**
     * 在本次构建上实测：对于被拒绝的 1 MiB 回调，有界扫描分配约 3.1 MB——
     * 解码后的请求体占主导，且没有创建任何按分隔符产生的 {@code String}。旧的基于
     * split 的解析器对同一请求体分配约 21.9 MB，即每个分隔符一个空 {@code String}。
     * 8 MiB 将两者分开，相对有界解析器有约 2.5 倍余量，
     * 相对旧解析器有约 2.7 倍余量。
     */
    private static final long ALLOCATION_CEILING_BYTES = 8L * 1024 * 1024;

    private AlipayAdapter adapter;
    private Method parse;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair alipayKeys = rsaKeyPair();
        adapter = new AlipayAdapter(
                new UnusedGateway(),
                java.time.Clock.systemUTC(),
                "2021000000000001",
                "2088000000000001",
                alipayKeys.getPublic(),
                URI.create("https://openapi.alipay.com/gateway.do"),
                URI.create("https://pay.example.com/alipay/callback"),
                URI.create("https://pay.example.com/orders/return"),
                "alipay-fingerprint",
                transactionNo -> {
                    throw new IllegalStateException("payment_expectation_not_found");
                });
        parse = AlipayAdapter.class.getDeclaredMethod("parseCallback", byte[].class);
        parse.setAccessible(true);
    }

    @Test
    void rejectedBodyAllocationDoesNotGrowWithTheBodySize() throws Exception {
        byte[] small = "&".repeat(SMALL_BODY_BYTES).getBytes(StandardCharsets.US_ASCII);
        byte[] large = "&".repeat(LARGE_BODY_BYTES).getBytes(StandardCharsets.US_ASCII);
        com.sun.management.ThreadMXBean allocations = allocationBean();
        if (allocations == null) {
            // 分配量统计是 HotSpot 的扩展功能；退而断言两种大小下
            // 该廉价拒绝依然会发生。
            assertThat(rejects(small)).isTrue();
            assertThat(rejects(large)).isTrue();
            return;
        }
        long smallBytes = bytesPerRejectedCall(small, allocations);
        long largeBytes = bytesPerRejectedCall(large, allocations);

        // 旧的基于 split 的解析器：每个分隔符一个空 String（24 B）→ 小请求体约 24 MB，大请求体
        // 约 96 MB。有界解析器：解码后的 char[] 随请求体增长，而逐对处理不会。
        assertThat(smallBytes)
                .as("bytes per rejected %d KiB callback", SMALL_BODY_BYTES / 1024)
                .isLessThan(ALLOCATION_CEILING_BYTES);
        assertThat(largeBytes)
                .as("bytes per rejected %d MiB callback", LARGE_BODY_BYTES / (1024 * 1024))
                .isLessThan(ALLOCATION_CEILING_BYTES);
    }

    @Test
    void theFullVerifyPathAlsoRejectsAnOversizedSeparatorBody() {
        byte[] body = "&".repeat(SMALL_BODY_BYTES).getBytes(StandardCharsets.US_ASCII);

        assertThat(catchThrowable(() -> adapter.verifyAndParseCallback(
                new PaymentAdapter.CallbackEnvelope(body, Map.of()))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alipay_callback");
    }

    @Test
    void theEnvelopeAlreadyRefusesBodiesPastItsOwnByteCeiling() {
        // 这是对本来就存在的边界的刻画，而不是对新扫描的刻画：审计要求
        // 在 split 之前有一道字节上限，而 PaymentAdapter.CallbackEnvelope 一直
        // 就强制执行着这样一道上限。在此固定下来，使它不会悄悄消失、
        // 让扫描成为对未认证请求体的唯一限制。
        assertThat(catchThrowable(() -> new PaymentAdapter.CallbackEnvelope(
                new byte[PaymentAdapter.MAX_CALLBACK_BYTES + 1], Map.of())))
                .as("one byte past the ceiling")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback body is too large");
        assertThat(catchThrowable(() -> new PaymentAdapter.CallbackEnvelope(
                new byte[PaymentAdapter.MAX_CALLBACK_BYTES], Map.of())))
                .as("at the ceiling")
                .isNull();
    }

    @Test
    void theHundredthParameterIsAcceptedAndTheHundredAndFirstIsRejected() {
        assertThat(catchThrowable(() -> parseDirectly(bodyWithParameters(MAX_CALLBACK_PARAMETERS))))
                .as("at the cap")
                .isNull();
        assertThat(catchThrowable(() -> parseDirectly(bodyWithParameters(MAX_CALLBACK_PARAMETERS + 1))))
                .as("one past the cap")
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_alipay_callback");
    }

    @Test
    void legitimateParameterSetStillKeepsItsDecodedNamesAndValues() throws Exception {
        byte[] body = ("app_id=2021000000000001&sign_type=RSA2&charset=UTF-8"
                + "&trade_status=TRADE_SUCCESS&opaque=a%2Fb+c%3Dd")
                .getBytes(StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, String> parameters = (Map<String, String>) parse.invoke(adapter, body);

        assertThat(parameters).containsEntry("app_id", "2021000000000001")
                .containsEntry("trade_status", "TRADE_SUCCESS")
                .containsEntry("opaque", "a/b c=d")
                .hasSize(5);
    }

    @Test
    void malformedPairsAreStillRejectedExactlyAsBefore() {
        for (String body : new String[] {
                "&", "a", "=b", "a&b=c", "a=1&", "a=1&&b=2", "a=1&a=2", "a=1&b"}) {
            assertThat(catchThrowable(() -> parseDirectly(body.getBytes(StandardCharsets.UTF_8))))
                    .as("body [%s]", body)
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("invalid_alipay_callback");
        }
    }

    private long bytesPerRejectedCall(byte[] body, com.sun.management.ThreadMXBean allocations)
            throws Exception {
        for (int index = 0; index < 4; index++) {
            try {
                parse.invoke(adapter, body);
            } catch (InvocationTargetException expectedRejection) {
                // 本测试中的每次调用都预期会被拒绝。
            }
        }
        long before = allocations.getCurrentThreadAllocatedBytes();
        for (int index = 0; index < SAMPLES; index++) {
            try {
                parse.invoke(adapter, body);
            } catch (InvocationTargetException expectedRejection) {
                // 本测试中的每次调用都预期会被拒绝。
            }
        }
        return (allocations.getCurrentThreadAllocatedBytes() - before) / SAMPLES;
    }

    private boolean rejects(byte[] body) {
        return catchThrowable(() -> parse.invoke(adapter, body)) != null;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean threadAllocations
                && threadAllocations.isThreadAllocatedMemorySupported()
                ? threadAllocations : null;
    }

    private static byte[] bodyWithParameters(int count) {
        StringBuilder body = new StringBuilder("charset=UTF-8");
        for (int index = 1; index < count; index++) {
            body.append('&');
            body.append("p").append(index).append("=v").append(index);
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Object parseDirectly(byte[] body) throws Exception {
        try {
            return parse.invoke(adapter, body);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** 解析器守卫在任何网关调用之前运行，因此这个网关绝不会被触达。 */
    private static final class UnusedGateway implements AlipayGateway {
        @Override
        public String createPagePayment(PaymentRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createWapPayment(PaymentRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentResult queryPayment(String merchantOrderNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentResult closePayment(String merchantOrderNo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult createRefund(RefundRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundResult queryRefund(RefundQueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BillResult downloadBill(java.time.LocalDate billDate) {
            throw new UnsupportedOperationException();
        }
    }
}
