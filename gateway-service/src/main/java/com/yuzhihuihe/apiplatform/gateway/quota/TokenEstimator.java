package com.yuzhihuihe.apiplatform.gateway.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    private final int charactersPerToken;
    private final double mediaSafetyFactor;
    private final long imageTokensPerImage;

    public TokenEstimator(
            int charactersPerToken,
            double mediaSafetyFactor) {
        this(charactersPerToken, mediaSafetyFactor, 1024);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TokenEstimator(
            @Value("${quota.estimator.characters-per-token:3}") int charactersPerToken,
            @Value("${quota.estimator.media-safety-factor:1.25}") double mediaSafetyFactor,
            @Value("${quota.estimator.image-tokens-per-image:1024}") long imageTokensPerImage) {
        if (charactersPerToken <= 0 || !Double.isFinite(mediaSafetyFactor) || mediaSafetyFactor < 1.0
                || imageTokensPerImage <= 0) {
            throw new IllegalArgumentException("invalid_token_estimator_configuration");
        }
        this.charactersPerToken = charactersPerToken;
        this.mediaSafetyFactor = mediaSafetyFactor;
        this.imageTokensPerImage = imageTokensPerImage;
    }

    public long estimate(EstimateCommand command) {
        if (command == null
                || command.requestedMaxOutputTokens() != null && command.requestedMaxOutputTokens() < 0
                || command.defaultOutputTokens() <= 0 || command.maxModelOutputTokens() <= 0
                || command.imageTokens() < 0 || command.fileTokens() < 0) {
            throw new IllegalArgumentException("invalid_token_estimate_request");
        }
        try {
            long textTokens = command.text() == null
                    ? 0
                    : estimateTextTokens(command.text().codePointCount(0, command.text().length()));
            double adjustedMediaTokens = Math.addExact(command.imageTokens(), command.fileTokens())
                    * mediaSafetyFactor;
            if (!Double.isFinite(adjustedMediaTokens) || adjustedMediaTokens > Long.MAX_VALUE) {
                throw new ArithmeticException("long overflow");
            }
            long mediaTokens = (long) Math.ceil(adjustedMediaTokens);
            long requestedOutput = command.requestedMaxOutputTokens() == null
                    ? command.defaultOutputTokens()
                    : command.requestedMaxOutputTokens();
            long outputTokens = Math.min(requestedOutput, command.maxModelOutputTokens());
            return Math.addExact(Math.addExact(textTokens, mediaTokens), outputTokens);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("invalid_token_estimate_request");
        }
    }

    public long estimateTextTokens(long codePointCount) {
        if (codePointCount < 0) {
            throw new IllegalArgumentException("invalid_token_estimate_request");
        }
        return codePointCount == 0 ? 0 : 1 + (codePointCount - 1) / charactersPerToken;
    }

    public long estimateImageTokens(long imageCount) {
        if (imageCount < 0) {
            throw new IllegalArgumentException("invalid_token_estimate_request");
        }
        try {
            return Math.multiplyExact(imageCount, imageTokensPerImage);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("invalid_token_estimate_request");
        }
    }

    public record EstimateCommand(
            String text,
            long imageTokens,
            long fileTokens,
            Long requestedMaxOutputTokens,
            long defaultOutputTokens,
            long maxModelOutputTokens) {
    }
}
