package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.yuzhihuihe.apiplatform.platform.sms.PhoneProtectionService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletAccountService;

/**
 * 登录绝不能把一个无界的、由攻击者控制的密码交给 BCrypt 编码器。
 *
 * <p>在给定强度下，编码器自身的开销每次调用都是恒定的 —— 针对本构建所用的
 * Spring Security 实测，{@code BCryptPasswordEncoder.matches()} 处理 70 个字符、1 MiB 与
 * 8 MiB 都约需 450 ms，因为 {@code checkpw} 经由 {@code hashpwforcheck} 路由，并在 72
 * 字节处截断，而不是对整个候选值做哈希。所以这是对输入设界，而非 CPU 放大：
 * 该守卫把攻击者规模的字符串挡在编码器之外，并使登录与
 * 注册路径已经执行的 72 字节策略保持一致。它必须在编码器被
 * 触及之前触发，并且必须用普通的凭证无效错误来应答，否则登录
 * 端点就会泄漏是哪个字段失败。
 */
class IdentityServicePasswordBoundTest {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    @Test
    void oversizedPasswordIsRejectedBeforeTheEncoderIsTouched() {
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
        IdentityService service = service(encoder);
        String oversized = "A1!a".repeat(1024 * 1024 / 4);

        assertThatThrownBy(() -> service.login("unknown@example.com", oversized))
                .isInstanceOf(IdentityApiException.class)
                .hasMessage("invalid_credentials")
                .extracting(error -> ((IdentityApiException) error).status().value())
                .isEqualTo(401);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void exactlyTheBcryptLimitStillReachesTheEncoder() {
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
        IdentityService service = service(encoder);
        String atLimit = "A1!a".repeat(BCRYPT_MAX_PASSWORD_BYTES / 4);

        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(BCRYPT_MAX_PASSWORD_BYTES);
        assertThatThrownBy(() -> service.login("unknown@example.com", atLimit))
                .isInstanceOf(IdentityApiException.class)
                .hasMessage("invalid_credentials");
        // 正常路径仍会执行比较，因此真实密码的行为没有变化。
        verify(encoder).matches(eq(atLimit), anyString());
    }

    @Test
    void oneBytePastTheLimitIsRefusedBeforeTheEncoderIsTouched() {
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
        IdentityService service = service(encoder);
        // 'é' 是两个 UTF-8 字节，因此 36 个是 72 字节，37 个是 74 字节。
        String overLimit = "é".repeat(37);

        assertThat(overLimit.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(BCRYPT_MAX_PASSWORD_BYTES);
        assertThatThrownBy(() -> service.login("unknown@example.com", overLimit))
                .isInstanceOf(IdentityApiException.class)
                .hasMessage("invalid_credentials");
        verify(encoder, never()).matches(any(), any());
    }

    private static IdentityService service(PasswordEncoder encoder) {
        IdentityRepositories repositories = mock(IdentityRepositories.class);
        IdentityAuthenticationRepository authentication =
                mock(IdentityAuthenticationRepository.class);
        when(repositories.authentication()).thenReturn(authentication);
        when(authentication.findPasswordUser(any(), any())).thenReturn(Optional.empty());

        PasswordLoginRateLimiter limiter = mock(PasswordLoginRateLimiter.class);
        when(limiter.inspect(any(), any()))
                .thenReturn(new PasswordLoginRateLimiter.LoginAttempt("account", "ip"));

        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        return new IdentityService(
                repositories,
                encoder,
                mock(JwtTokenService.class),
                empty.getBeanProvider(SmsChallengeService.class),
                empty.getBeanProvider(SmsProofService.class),
                empty.getBeanProvider(PhoneProtectionService.class),
                mock(WalletAccountService.class),
                empty.getBeanProvider(LoginActivityService.class),
                provider(PasswordLoginRateLimiter.class, limiter),
                empty.getBeanProvider(LoginHandoffService.class),
                empty.getBeanProvider(TerminalSessionService.class),
                empty.getBeanProvider(OpaqueTokenService.class),
                authentication);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(
            Class<T> type, T instance) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean(type.getName(), instance);
        return factory.getBeanProvider(type);
    }
}
