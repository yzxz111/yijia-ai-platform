package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import com.yuzhihuihe.apiplatform.gateway.file.FileContentValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class LegacyDocumentConverterClient {

    private final WebClient webClient;
    private final String authToken;
    private final long maxOutputBytes;
    private final Duration timeout;
    private final FileContentValidator validator;

    @Autowired
    public LegacyDocumentConverterClient(
            WebClient.Builder webClientBuilder,
            MultimodalProperties properties,
            FileContentValidator validator) {
        this(
                webClientBuilder,
                parseBaseUrl(properties.getConverterBaseUrl()),
                properties.getConverterAuthToken(),
                properties.getMaxConvertedOutput().toBytes(),
                properties.getPreprocessingTimeout(),
                validator,
                properties.isConverterAllowInsecureLocal(),
                properties.getConverterAllowedHosts());
    }

    LegacyDocumentConverterClient(
            WebClient.Builder webClientBuilder,
            URI baseUrl,
            String authToken,
            long maxOutputBytes,
            Duration timeout,
            FileContentValidator validator) {
        this(webClientBuilder, baseUrl, authToken, maxOutputBytes, timeout, validator,
                false, Set.of());
    }

    LegacyDocumentConverterClient(
            WebClient.Builder webClientBuilder,
            URI baseUrl,
            String authToken,
            long maxOutputBytes,
            Duration timeout,
            FileContentValidator validator,
            boolean allowInsecureLocal,
            Set<String> allowedHosts) {
        if (!validBaseUrl(baseUrl, allowInsecureLocal, allowedHosts)
                || authToken == null || authToken.isBlank()
                || "local-document-converter-token-change-me".equals(authToken)
                || maxOutputBytes <= 0
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || validator == null) {
            throw new IllegalArgumentException("invalid_document_converter_configuration");
        }
        int inMemoryLimit = (int) Math.min(Integer.MAX_VALUE, maxOutputBytes);
        this.webClient = webClientBuilder.clone()
                .baseUrl(baseUrl.toString())
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(inMemoryLimit))
                        .build())
                .build();
        this.authToken = authToken;
        this.maxOutputBytes = maxOutputBytes;
        this.timeout = timeout;
        this.validator = validator;
    }

    private static URI parseBaseUrl(String value) {
        try {
            return URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("invalid_document_converter_configuration");
        }
    }

    private static boolean validBaseUrl(
            URI baseUrl, boolean allowInsecureLocal, Set<String> allowedHosts) {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null
                || (baseUrl.getRawPath() != null
                        && !baseUrl.getRawPath().isBlank()
                        && !"/".equals(baseUrl.getRawPath()))) {
            return false;
        }
        if ("https".equalsIgnoreCase(baseUrl.getScheme())) {
            return true;
        }
        if (!"http".equalsIgnoreCase(baseUrl.getScheme()) || !allowInsecureLocal) {
            return false;
        }
        Set<String> normalizedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return normalizedHosts.contains(baseUrl.getHost().toLowerCase(Locale.ROOT));
    }

    public Mono<ConvertedDocument> convert(
            String filename, String mimeType, byte[] content) {
        return Mono.defer(() -> {
            ConvertedFormat expected = sourceFormat(filename);
            validator.validateDocument(filename, mimeType, content, maxOutputBytes);
            return webClient.post()
                    .uri("/convert")
                    .headers(headers -> headers.setBearerAuth(authToken))
                    .header("X-Source-Format", expected.sourceFormat())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(content)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            ResponseStatusException mapped = mapStatus(response.statusCode().value());
                            return response.releaseBody().then(Mono.error(mapped));
                        }
                        String responseType = response.headers().contentType()
                                .map(MediaType::toString).orElse("");
                        if (!expected.mimeType().equalsIgnoreCase(responseType)) {
                            return response.releaseBody().then(Mono.error(conversionFailed()));
                        }
                        return response.bodyToMono(byte[].class)
                                .switchIfEmpty(Mono.error(conversionFailed()))
                                .flatMap(bytes -> validateConverted(expected, bytes));
                    });
        })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, ignored -> preprocessingTimeout())
                .onErrorMap(WebClientRequestException.class, ignored -> converterUnavailable())
                .onErrorMap(DataBufferLimitException.class,
                        ignored -> new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large"));
    }

    private Mono<ConvertedDocument> validateConverted(ConvertedFormat format, byte[] content) {
        if (content.length == 0 || content.length > maxOutputBytes) {
            return Mono.error(content.length > maxOutputBytes
                    ? new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large")
                    : conversionFailed());
        }
        try {
            validator.validateDocument(
                    "converted" + format.extension(), format.mimeType(), content, maxOutputBytes);
            return Mono.just(new ConvertedDocument(format, content));
        } catch (ResponseStatusException invalidOutput) {
            return Mono.error(conversionFailed());
        }
    }

    private ConvertedFormat sourceFormat(String filename) {
        String normalized = filename == null ? "" : filename.strip().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".doc")) {
            return ConvertedFormat.DOCX;
        }
        if (normalized.endsWith(".xls")) {
            return ConvertedFormat.XLSX;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_file_content");
    }

    private ResponseStatusException mapStatus(int status) {
        if (status == 503 || status == 401 || status >= 500 && status != 504) {
            return converterUnavailable();
        }
        if (status == 504) {
            return preprocessingTimeout();
        }
        if (status == 413) {
            return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
        }
        return conversionFailed();
    }

    private static ResponseStatusException conversionFailed() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "document_conversion_failed");
    }

    private static ResponseStatusException converterUnavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "document_converter_unavailable");
    }

    private static ResponseStatusException preprocessingTimeout() {
        return new ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT, "multimodal_preprocessing_timeout");
    }

    public enum ConvertedFormat {
        DOCX("doc", ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        XLSX("xls", ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        private final String sourceFormat;
        private final String extension;
        private final String mimeType;

        ConvertedFormat(String sourceFormat, String extension, String mimeType) {
            this.sourceFormat = sourceFormat;
            this.extension = extension;
            this.mimeType = mimeType;
        }

        public String sourceFormat() {
            return sourceFormat;
        }

        public String extension() {
            return extension;
        }

        public String mimeType() {
            return mimeType;
        }
    }

    public record ConvertedDocument(ConvertedFormat format, byte[] content) {

        public ConvertedDocument {
            content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }
}
