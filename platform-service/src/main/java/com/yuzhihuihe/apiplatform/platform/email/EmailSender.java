package com.yuzhihuihe.apiplatform.platform.email;

public interface EmailSender {
    void sendVerificationCode(String email, String code);
}
