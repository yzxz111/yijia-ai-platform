package com.yuzhihuihe.apiplatform.platform.payment;

public enum PaymentProduct {
    MOCK,
    WECHAT_NATIVE,
    WECHAT_H5,
    ALIPAY_PAGE,
    ALIPAY_WAP;

    public PaymentProvider provider() {
        return switch (this) {
            case MOCK -> PaymentProvider.MOCK;
            case WECHAT_NATIVE, WECHAT_H5 -> PaymentProvider.WECHAT_PAY;
            case ALIPAY_PAGE, ALIPAY_WAP -> PaymentProvider.ALIPAY;
        };
    }
}
