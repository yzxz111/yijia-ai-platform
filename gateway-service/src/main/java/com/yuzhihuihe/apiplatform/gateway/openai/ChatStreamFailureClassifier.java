package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.channel.AbortedException;

/**
 * 首段已发出之后的失败，到底是谁断的。
 *
 * <p><b>为什么需要这个类。</b>响应提交之后（HTTP 200 与 {@code text/event-stream} 已经发出） 遇到的异常，此前只有两条出口：识别为传输层失败的走
 * {@link ProviderAdapter.ProviderException#ofTransport}，**其余全部**被写成 {@code ProviderException(502,
 * "upstream_stream_interrupted")}。后者是一个兜底值，却同时承担了
 * 「上游掐断」「网关自身异常」「下游客户端断开」三种完全不同的含义，而且那条路径**一行日志都没有**。
 *
 * <p>后果在 2026-10-15 的复测里直接显现：三项真实复测都在 3.4–9.6 秒失败，用量表里 {@code error_summary} 全是 {@code
 * ProviderException(status=502: upstream_stream_interrupted)}，
 * 而这个词组按定义就等于「上游流中断」——于是一次**尚未定责**的失败被当成已定责的上游故障， 排查只能停在猜测上。注意其它原因都有各自专属的码 （{@code
 * provider_unreachable} / {@code provider_stream_missing_done} / {@code
 * invalid_provider_stream_chunk} / {@code provider_attempt_timeout}），
 * 它们一个都没出现，正说明这批失败没有被上游故障这条路解释掉。
 *
 * <p><b>本类做两件事，都不改变对客户端的契约。</b>
 *
 * <ol>
 *   <li><b>把兜底值换成两个可分辨的原因码。</b>兜底值 {@code upstream_stream_interrupted} 从此
 *       不再由这里产生——它原本的语义是「上游流中断」，而这条分支恰恰是唯一无法证明上游中断的 地方（真正的上游传输失败有它自己的两个码，见下）。现在：已经带着受控原因码的 {@code
 *       ProviderException} **原样返回**（它是**已定责**的，重新包装只会丢掉原因码，见
 *       {@link #reason}），客户端主动断开记 {@link #CLIENT_ABORTED_STREAM}，其余难以归类的记 {@link
 *       #UNKNOWN_STREAM_FAILURE}，而真正的传输层失败继续沿用既有的 {@code provider_unreachable} /
 *       {@code provider_attempt_timeout}。这些分支共用同一个 {@code error.code} （{@code
 *       provider_error}），因此**客户端的重试与提示逻辑一行都不用改**——变的只是 诊断用的 {@code reason} 与用量表里的定责列。
 *   <li><b>把异常本身写进日志。</b>这一条比第 1 条更重要：不管分类写得多细，总会有下一个 没人预料到的异常。只要它在日志里留下了类名、根因与消息，下一次就能直接读出来，而不是
 *       再一次从「上游流中断」这个词往回猜。日志保留上游原文是有意的取舍——它只进内部日志， 不进客户端响应，也不进数据库列（那两处的口径见 {@link
 *       ChatCompletionUsageAccounting#safeErrorSummary}）。
 * </ol>
 *
 * <p><b>归属判定的证据方式。</b>{@code AbortedException} 的含义是「连接在写完成之前被关闭」，
 * 它<b>不</b>自带方向：上游那条连接上也可能发生同样的事。因此本类不靠异常类型下结论，而是要求 看到它确实是从<b>下游写回</b>这条路上抛出来的（栈帧证据，见 {@link
 * #raisedWhileWritingToTheClient}），否则归到 {@link #UNKNOWN_STREAM_FAILURE}——
 * 一个诚实的「不知道」，好过一个猜错的责任方。这个归属可以用日志证伪：日志里同时写了类名与根因， 若哪天它其实是上游造成的，读日志的人一眼就能推翻。
 */
final class ChatStreamFailureClassifier {

    /**
     * 客户端在流中途断开（自己关了连接），常见的两种触发：用户点了停止，或客户端自己的 计时器/看门狗到了点。它**不是**上游故障，因此不能记成上游故障——那会污染渠道健康度，
     * 也会让「上游到底稳不稳」这个判断失去依据。
     */
    static final String CLIENT_ABORTED_STREAM = "client_aborted_stream";

    /**
     * 走到兜底分支、且异常类型不在任何已知分类里的失败。
     *
     * <p>这个名字是刻意刺眼的：它出现就代表「网关遇到了一个自己没预料到的失败」， 应当去查这条请求的日志，而不是像从前那样被读成一次普通的上游流中断。
     */
    static final String UNKNOWN_STREAM_FAILURE = "unknown_stream_failure";

    /** 写进日志的技术细节长度上限；异常消息可能是整篇上游 HTML。 */
    private static final int MAX_DETAIL_CHARS = 300;

    /**
     * 这些名字只是包装层，真正的信息在它们的 cause 上。
     *
     * <p>{@code RuntimeException} / {@code IllegalStateException} **刻意不在**这个集合里： 它们经常就是失败本身（例如
     * {@code attemptTimeout()} 之外的网关内部前置条件失败）， 把它们当包装层跳过，恰好会把最有价值的那条线索从日志里抹掉。
     */
    private static final Set<String> WRAPPER_SIMPLE_NAMES =
            Set.of("CompletionException", "ExecutionException", "ReactiveException", "Throwable");

    private static final Logger LOG = LoggerFactory.getLogger(ChatStreamFailureClassifier.class);

    private ChatStreamFailureClassifier() {}

    /**
     * 把首段之后的失败收窄到一个原因码，并把它连同异常本身记进日志。
     *
     * <p>返回的异常是交给客户端的终态：{@code error.code} 始终落在原有的 {@code provider_error} 上（见 {@code
     * OpenAiErrorMapper}），因此不会改变任何客户端的 分支判断；{@code reason} 才是这次新加的诊断信号。
     */
    static ProviderAdapter.ProviderException classify(
            Throwable error,
            String requestId,
            int attemptIndex,
            String publicModelAlias,
            ProviderAdapter.Channel channel,
            Instant startedAt) {
        ProviderAdapter.ProviderException recorded = reason(error, requestId);
        log(
                error,
                requestId,
                attemptIndex,
                publicModelAlias,
                channel,
                startedAt,
                recorded.reason());
        return recorded;
    }

    private static ProviderAdapter.ProviderException reason(Throwable error, String requestId) {
        if (error == null) {
            return new ProviderAdapter.ProviderException(502, UNKNOWN_STREAM_FAILURE);
        }
        // **已经定好类的失败不要再包装一次。**
        //
        // 这个类的职责是给「没人归过类」的异常找一个诚实的名字，而不是给一个**已经带着精确
        // 原因码**的失败改名。此前这里没有这一支，于是每一个走到兜底的 ProviderException
        // 都会被套上一层 502/unknown_stream_failure：原因码没了，状态码也被换成了 502。
        //
        // 代价在 2026-10-16 的排查里直接显现：上游天翼云在长度截断时多发一帧
        // choices:[] （见 ChatCompletionResponseProtocol#validateStreamChunk），网关据此抛出
        // invalid_provider_stream_chunk —— 一个**精确**的原因码。但日志、用量表的
        // error_category、以及交给客户端的失败帧，全都写成了 unknown_stream_failure，
        // 也就是「网关遇到了一个自己没预料到的失败，去查这条请求的日志」。而日志里唯一
        // 有用的那条线索（invalid_provider_stream_chunk）恰好被这一支抹掉了，排查只能从
        // 「上游流中断」这个词往回猜。
        //
        // 放在最前面是必须的：这些异常不是 AbortedException 也不是传输层失败，
        // 走完下面两支只会落到同一个兜底上。判空同样必须——reason() 为 null 说明它自己
        // 也没能给出受控原因码，那种情况才轮得到下面去猜。
        if (error instanceof ProviderAdapter.ProviderException classified
                && classified.reason() != null) {
            return classified;
        }
        // **顺序是被测出来的，不是随手排的。** reactor-netty 的 AbortedException 是
        // RuntimeException，但它经常被包在一个 IOException 里（例如
        // ClosedChannelException），于是 isTransportFailure 会先把整条链判成「传输层失败」。
        // 先判客户端断开，才认得出那个真正发生的事；否则这一支永远不可达，
        // 客户端断开会被记成 provider_unreachable——把一个下游现象算到上游头上。
        if (isClientAbort(error)) {
            return new ProviderAdapter.ProviderException(502, CLIENT_ABORTED_STREAM);
        }
        Throwable transport =
                firstMatch(error, ProviderAdapter.ProviderException::isTransportFailure);
        if (transport != null) {
            // 适配器已经把这类失败映射成 provider_unreachable / provider_attempt_timeout，
            // 走到这里说明失败发生在适配器之外（例如下游写入路径上的 IOException）。
            // 沿用既有原因码：对客户端而言它是同一类可重试的终态，而在定责上它确实落在
            // 「网络 / 上游」这一侧，不需要再发明新词。
            return ProviderAdapter.ProviderException.ofTransport(transport);
        }
        return new ProviderAdapter.ProviderException(502, UNKNOWN_STREAM_FAILURE);
    }

    private static boolean isClientAbort(Throwable error) {
        Throwable aborted = firstMatch(error, candidate -> candidate instanceof AbortedException);
        // **光看异常类型不足以指名客户端。** AbortedException 的含义是「连接在写完成之前被关闭」，
        // 它并不自带方向：上游那条连接上也可能发生同样的事。因此还要有栈上的证据，证明这次失败
        // 确实来自下游写回这条路。看不出来就不要猜，归到 unknown_stream_failure ——
        // 一个诚实的「不知道」比一个猜错的责任方有用得多。
        return aborted != null && cameFromTheDownstreamWrite(aborted);
    }

    private static boolean cameFromTheDownstreamWrite(Throwable error) {
        boolean downstream = false;
        StackTraceElement[] frames = error.getStackTrace();
        for (int index = 0; frames != null && index < frames.length; index++) {
            String declaring = frames[index].getClassName();
            if (!declaring.startsWith("reactor.netty.http.server")
                    && !declaring.startsWith("org.springframework.web.server")
                    && !declaring.startsWith("org.springframework.http.server.reactive")) {
                continue;
            }
            downstream = true;
            if (frames[index].getMethodName().startsWith("send")) {
                return true;
            }
        }
        // 走到这里说明栈上出现了下游这条路的帧，但没有一个明确的写方法名。此时只有一种情况
        // 值得据此指名客户端：异常自己说了「连接在发送之前就关了」——那是 reactor-netty 在
        // 写回失败时用的原话。
        if (!downstream || error.getMessage() == null) {
            return false;
        }
        return error.getMessage().contains("BEFORE send operation");
    }

    /**
     * 沿 cause 链找第一个满足条件的异常。
     *
     * <p>必须走链，不能只看最外层：真实的失败几乎总是被包了一层（{@code CompletionException}、 {@code ReactiveException}、或
     * reactor-netty 自己的包装），而在这条链上先遇到 {@code IOException} 还是先遇到 {@code
     * AbortedException}，会把同一个故障判成两种不同的责任方。 因此两个判据都用同一个「沿链找」的口径，谁更具体谁先判。
     */
    private static Throwable firstMatch(Throwable error, Predicate<Throwable> match) {
        Throwable current = error;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (match.test(current)) {
                return current;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    private static void log(
            Throwable error,
            String requestId,
            int attemptIndex,
            String publicModelAlias,
            ProviderAdapter.Channel channel,
            Instant startedAt,
            String reason) {
        LOG.warn(
                message(
                        error,
                        requestId,
                        attemptIndex,
                        publicModelAlias,
                        channel,
                        startedAt,
                        reason));
    }

    /**
     * 日志正文本身。（这条日志是本次修复的主要产物，所以它整行——而不只是里面的 detail 字段—— 必须可被测试断言。写成纯函数既保住了可测性，也不必为了抓日志去动测试的日志配置。）
     *
     * <p>字段顺序固定，便于 {@code grep} 与人工阅读；每个值都自带有名字段，避免出现一串 没有标签的数字。
     */
    static String message(
            Throwable error,
            String requestId,
            int attemptIndex,
            String publicModelAlias,
            ProviderAdapter.Channel channel,
            Instant startedAt,
            String reason) {
        return "chat stream failed after the first byte: request="
                + requestId
                + " reason="
                + reason
                + " attempt="
                + attemptIndex
                + " model="
                + publicModelAlias
                + " channel="
                + (channel == null ? "unknown" : channel.id())
                + " providerType="
                + (channel == null ? "unknown" : channel.providerType())
                + " upstreamModel="
                + (channel == null ? "unknown" : channel.upstreamModel())
                + " elapsedMs="
                + (startedAt == null ? -1 : Duration.between(startedAt, Instant.now()).toMillis())
                + " timeoutMs="
                + (channel == null ? -1 : channel.timeoutMs())
                + " detail="
                + describe(error);
    }

    /**
     * 一条可在日志里读、也可在测试里断言的技术细节：异常类名 + 根因类名 + 有界消息。
     *
     * <p>刻意与写进数据库/响应体的文本分开：这里允许出现上游原文（内部日志），而 {@code usage_log.error_summary} 与客户端响应只允许受控标识符（见
     * {@link ChatCompletionUsageAccounting#safeErrorSummary}）。两者混用会出现两种坏结果：
     * 要么日志里没有原文（无法诊断），要么数据库里出现厂商文本（不可控）。
     */
    static String describe(Throwable error) {
        if (error == null) {
            return "none";
        }
        Throwable root = rootCause(error);
        StringBuilder detail = new StringBuilder(exceptionName(error));
        if (root != error) {
            detail.append(" <- ").append(exceptionName(root));
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getMessage();
        }
        if (message != null && !message.isBlank()) {
            detail.append(": ").append(bounded(message));
        }
        return detail.toString();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 8; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    /** 包装层的类名对诊断没有价值（{@code CompletionException} 换不来任何信息）， 因此跳过它们直接报最内层的真实类型。 */
    private static String exceptionName(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 8; depth++) {
            if (!WRAPPER_SIMPLE_NAMES.contains(current.getClass().getSimpleName())
                    || current.getCause() == null
                    || current.getCause() == current) {
                return current.getClass().getName();
            }
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private static String bounded(String message) {
        String flattened = message.replace('\n', ' ').replace('\r', ' ').strip();
        return flattened.length() <= MAX_DETAIL_CHARS
                ? flattened
                : flattened.substring(0, MAX_DETAIL_CHARS) + "…";
    }
}
