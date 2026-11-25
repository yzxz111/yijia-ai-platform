package com.yuzhihuihe.apiplatform.platform.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

class SmtpEmailSenderTest {

    @Test
    void springLoadsOptionalReplyToOnlyWhenEmailIsEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(SmtpEmailSender.class);
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(SmtpEmailSender.class));
        contextRunner.withPropertyValues("email.enabled=true", "email.reply-to=support@example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(SmtpEmailSender.class), "replyTo"))
                            .isEqualTo("support@example.com");
                });
        contextRunner.withPropertyValues("email.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(SmtpEmailSender.class), "replyTo"))
                    .isEqualTo("");
        });
    }

    @Test
    void sendsVerificationWithConfiguredFromNameAndReplyTo() throws Exception {
        SmtpEmailSender sender = sender(" support@example.com ");
        MimeMessage message = message();
        JavaMailSenderImpl transport = transport(sender, message);

        sender.sendVerificationCode("recipient@example.com", "123456");

        verify(transport).send(message);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@example.com");
        assertThat(from.getPersonal()).isEqualTo("宇智汇和");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress())
                .isEqualTo("support@example.com");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@example.com");
        assertThat(message.getSubject()).isEqualTo("一加AI中台验证码");
        assertThat(message.getContent().toString()).contains("123456", "5 分钟");
    }

    @Test
    void omitsReplyToHeaderWhenNotConfigured() throws Exception {
        for (String replyTo : new String[] {null, "", "   "}) {
            SmtpEmailSender sender = sender(replyTo);
            MimeMessage message = message();
            transport(sender, message);

            sender.sendVerificationCode("recipient@example.com", "123456");

            assertThat(message.getHeader("Reply-To")).isNull();
        }
    }

    @Test
    void usesAuthenticatedSslWithCertificateHostnameVerificationAndBoundedTimeouts() {
        JavaMailSenderImpl transport = (JavaMailSenderImpl)
                ReflectionTestUtils.getField(sender(""), "mailSender");

        assertThat(transport.getPort()).isEqualTo(465);
        assertThat(transport.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "5000")
                .containsEntry("mail.smtp.writetimeout", "5000");
    }

    @Test
    void wrapsAuthenticationFailureWithoutExposingProviderDetails() {
        SmtpEmailSender sender = sender("");
        JavaMailSenderImpl transport = transport(sender, message());
        doThrow(new MailAuthenticationException("private-provider-detail"))
                .when(transport).send(any(MimeMessage.class));

        assertThatThrownBy(() -> sender.sendVerificationCode("recipient@example.com", "123456"))
                .isInstanceOf(SmtpEmailSender.EmailDeliveryException.class)
                .hasMessage("email_delivery_failed")
                .hasCauseInstanceOf(MailAuthenticationException.class);
    }

    @Test
    void incompleteConfigurationDoesNotAttemptDelivery() {
        SmtpEmailSender sender = new SmtpEmailSender(
                "smtp.example.com", 465, "noreply@example.com", "", "noreply@example.com", "Sender", "");

        assertThatThrownBy(() -> sender.sendVerificationCode("recipient@example.com", "123456"))
                .isInstanceOf(SmtpEmailSender.EmailDeliveryException.class)
                .hasMessage("email_disabled");
    }

    private SmtpEmailSender sender(String replyTo) {
        return new SmtpEmailSender("smtp.example.com", 465, "noreply@example.com", "test-only-password",
                "noreply@example.com", "宇智汇和", replyTo);
    }

    private MimeMessage message() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private JavaMailSenderImpl transport(SmtpEmailSender sender, MimeMessage message) {
        JavaMailSenderImpl transport = mock(JavaMailSenderImpl.class);
        when(transport.getHost()).thenReturn("smtp.example.com");
        when(transport.getUsername()).thenReturn("noreply@example.com");
        when(transport.getPassword()).thenReturn("test-only-password");
        when(transport.createMimeMessage()).thenReturn(message);
        ReflectionTestUtils.setField(sender, "mailSender", transport);
        return transport;
    }
}
