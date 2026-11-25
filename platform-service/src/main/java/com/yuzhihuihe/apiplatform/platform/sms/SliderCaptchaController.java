package com.yuzhihuihe.apiplatform.platform.sms;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/platform/auth/slider-captcha")
public class SliderCaptchaController {

    private final SliderCaptchaService captchas;
    private final SmsRequestFingerprintService fingerprints;

    public SliderCaptchaController(
            SliderCaptchaService captchas,
            SmsRequestFingerprintService fingerprints) {
        this.captchas = captchas;
        this.fingerprints = fingerprints;
    }

    @PostMapping("/challenges")
    ResponseEntity<ChallengeResponse> issue(HttpServletRequest request) {
        SliderCaptchaService.Challenge challenge = captchas.issue(fingerprints.fingerprint(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ChallengeResponse.from(challenge));
    }

    @PostMapping("/challenges/{id}/verify")
    ProofResponse verify(
            @PathVariable UUID id,
            @RequestBody VerifyRequest request,
            HttpServletRequest servletRequest) {
        SliderCaptchaService.VerifiedProof verified = captchas.verify(
                id,
                request == null ? null : request.offsetX(),
                fingerprints.fingerprint(servletRequest));
        return new ProofResponse(verified.proof(), verified.expiresAt());
    }

    record VerifyRequest(Integer offsetX) { }

    record ChallengeResponse(
            UUID id,
            String backgroundImage,
            String pieceImage,
            int pieceY,
            int imageWidth,
            int imageHeight,
            int pieceSize,
            Instant expiresAt) {

        static ChallengeResponse from(SliderCaptchaService.Challenge challenge) {
            return new ChallengeResponse(
                    challenge.id(),
                    challenge.backgroundImage(),
                    challenge.pieceImage(),
                    challenge.pieceY(),
                    challenge.imageWidth(),
                    challenge.imageHeight(),
                    challenge.pieceSize(),
                    challenge.expiresAt());
        }
    }

    record ProofResponse(String captchaProof, Instant expiresAt) { }
}
