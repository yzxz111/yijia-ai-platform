package com.yuzhihuihe.apiplatform.platform.identity.api;

import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import java.util.UUID;

public final class ProfileContracts {

    private ProfileContracts() {}

    public record ChangePasswordRequest(
            String currentPassword, String newPassword, String smsProof,
            String invitationCode) {}

    public record PhoneChallengeRequest(SmsPurpose purpose, String phone) {}

    public record BindPhoneRequest(
            UUID challengeId, SmsPurpose purpose, String currentPhoneProof) {}

    public record SmsPreferenceRequest(Boolean enabled) {}

    public record DisplayNameRequest(String displayName) {}

    public record DisplayNameResponse(String displayName) {}

    public record LoginDeviceLimitRequest(Integer maxLoginDevices) {}

    public record LoginDeviceLimitResponse(int maxLoginDevices) {}
}
