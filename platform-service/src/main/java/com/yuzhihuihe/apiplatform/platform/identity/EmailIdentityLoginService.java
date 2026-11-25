package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.UUID;

import com.yuzhihuihe.apiplatform.platform.email.EmailVerificationService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** 邮箱验证码核销与账号创建共享事务，协议校验失败时允许重试同一验证码。 */
@Service
public class EmailIdentityLoginService {
    private final EmailVerificationService verifications;
    private final IdentityService identities;

    public EmailIdentityLoginService(EmailVerificationService verifications, IdentityService identities) {
        this.verifications = verifications;
        this.identities = identities;
    }

    @Transactional(noRollbackFor = SmsApiException.class)
    public IdentityService.IdentityLoginResult authenticate(
            UUID challengeId, String email, String code, Boolean accepted, String version,
            LoginActivityService.LoginContext context) {
        // 错误验证码的尝试次数必须提交，不能随登录失败回滚；校验通过后返回的是
        // 「已验证的注册凭据」而不是裸邮箱——合并自 gitee 的注册验证链路把身份创建收口到
        // IdentityService.authenticateWithVerifiedEmail(VerifiedRegistration, …)，
        // 后者内部再核销该凭据，因此这里必须传凭据本体（该方法是它唯一的调用方）。
        EmailVerificationService.VerifiedRegistration verified =
                verifications.verifyForIdentity(challengeId, email, code);
        try {
            return identities.authenticateWithVerifiedEmail(verified, accepted, version, context);
        } catch (RuntimeException failure) {
            // 验证码已校验通过，此后的协议或账号错误需要一并撤销核销。
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw failure;
        }
    }
}
