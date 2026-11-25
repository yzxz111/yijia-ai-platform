package com.yuzhihuihe.apiplatform.platform.sms;

public enum SmsPurpose {
    REGISTER("REGISTER_CODE", false, false),
    LOGIN("LOGIN_CODE", false, false),
    PASSWORD_RESET("PASSWORD_CODE", false, false),
    CHANGE_PASSWORD("PASSWORD_CODE", true, true),
    PAYMENT_CONFIRM("PAYMENT_CONFIRM_CODE", true, true),
    API_KEY_RESET("API_KEY_RESET_CODE", true, true),
    PHONE_BIND("PHONE_CHANGE_CODE", true, false),
    PHONE_CHANGE("PHONE_CHANGE_CODE", true, false),
    PHONE_CHANGE_CONFIRM("PHONE_CHANGE_CODE", true, true);

    private final String templateKey;
    private final boolean authenticated;
    private final boolean issuesProof;

    SmsPurpose(String templateKey, boolean authenticated, boolean issuesProof) {
        this.templateKey = templateKey;
        this.authenticated = authenticated;
        this.issuesProof = issuesProof;
    }

    public String templateKey() {
        return templateKey;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public boolean issuesProof() {
        return issuesProof;
    }
}
