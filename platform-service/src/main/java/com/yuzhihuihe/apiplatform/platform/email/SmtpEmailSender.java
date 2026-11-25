package com.yuzhihuihe.apiplatform.platform.email;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "email.enabled", havingValue = "true")
public final class SmtpEmailSender implements EmailSender {

    private final JavaMailSenderImpl mailSender;
    private final String from;
    private final String fromName;
    private final String replyTo;

    public SmtpEmailSender(
            @Value("${email.smtp.host:}") String host,
            @Value("${email.smtp.port:465}") int port,
            @Value("${email.smtp.username:}") String username,
            @Value("${email.smtp.password:}") String password,
            @Value("${email.from:}") String from,
            @Value("${email.from-name:一加AI中台}") String fromName,
            @Value("${email.reply-to:}") String replyTo) {
        this.mailSender = new JavaMailSenderImpl();
        this.mailSender.setHost(host);
        this.mailSender.setPort(port);
        this.mailSender.setUsername(username);
        this.mailSender.setPassword(password);
        Properties properties = this.mailSender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.ssl.enable", "true");
        properties.put("mail.smtp.ssl.checkserveridentity", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "5000");
        properties.put("mail.smtp.writetimeout", "5000");
        this.from = from;
        this.fromName = fromName;
        this.replyTo = replyTo == null ? "" : replyTo.trim();
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        if (mailSender.getHost() == null || mailSender.getHost().isBlank()
                || mailSender.getUsername() == null || mailSender.getUsername().isBlank()
                || mailSender.getPassword() == null || mailSender.getPassword().isBlank()
                || from == null || from.isBlank()) {
            throw new EmailDeliveryException("email_disabled");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from, fromName);
            if (!replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setTo(email);
            helper.setSubject("一加AI中台验证码");
            helper.setText("您的验证码是 " + code + "，5 分钟内有效。如非本人操作请忽略。", false);
            mailSender.send(message);
        } catch (MessagingException | java.io.UnsupportedEncodingException | MailException error) {
            throw new EmailDeliveryException("email_delivery_failed", error);
        }
    }

    static final class EmailDeliveryException extends RuntimeException {
        EmailDeliveryException(String message) { super(message); }
        EmailDeliveryException(String message, Throwable cause) { super(message, cause); }
    }
}
