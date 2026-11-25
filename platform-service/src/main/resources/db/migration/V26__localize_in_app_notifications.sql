UPDATE platform.in_app_notification notification
SET title = CASE event.event_type
        WHEN 'WALLET_USAGE_SETTLED' THEN 'API 用量已结算'
        WHEN 'WALLET_USAGE_DAILY_SUMMARY' THEN '每日用量汇总'
        WHEN 'BALANCE_LOW' THEN '余额不足'
        WHEN 'ACCOUNT_FROZEN' THEN '账户已冻结'
        WHEN 'ACCOUNT_BANNED' THEN '账户已封禁'
        WHEN 'ACCOUNT_UNFROZEN' THEN '账户已恢复'
        WHEN 'RISK_LOGIN' THEN '异常登录提醒'
        WHEN 'SUPPORT_TICKET_COMPLETED' THEN '支持工单已完成'
        WHEN 'ORDER_PAYMENT_FAILED' THEN '支付失败'
        WHEN 'ORDER_TIMED_OUT' THEN '订单已超时'
        WHEN 'PACKAGE_PAYMENT_SUCCEEDED' THEN '套餐已生效'
        WHEN 'BALANCE_RECHARGE_SUCCEEDED' THEN '余额充值成功'
        WHEN 'REFUND_SUCCEEDED' THEN '退款已完成'
        WHEN 'PACKAGE_EXPIRES_IN_3_DAYS' THEN '套餐即将到期'
        WHEN 'PACKAGE_EXPIRED' THEN '套餐已到期'
        WHEN 'API_RATE_LIMITED' THEN 'API 调用受限'
        ELSE notification.title
    END,
    summary = CASE event.event_type
        WHEN 'WALLET_USAGE_SETTLED' THEN
            '模型 ' || COALESCE(event.payload->>'logicalModel', '-')
            || ' 本次消费 ' || COALESCE(event.payload->>'amountFen', '0')
            || ' 分，可用余额 ' || COALESCE(event.payload->>'availableBalanceFen', '0') || ' 分'
        WHEN 'WALLET_USAGE_DAILY_SUMMARY' THEN
            '今日 API 调用费用 ' || COALESCE(event.payload->>'costMoney', '0')
            || '，当前余额 ' || COALESCE(event.payload->>'balance', '0')
        WHEN 'BALANCE_LOW' THEN
            '可用余额已低于 ' || COALESCE(event.payload->>'warnMoney', '预警值')
        WHEN 'ACCOUNT_FROZEN' THEN '您的账户已冻结并进入安全审核'
        WHEN 'ACCOUNT_BANNED' THEN '您的账户已被封禁'
        WHEN 'ACCOUNT_UNFROZEN' THEN '您的账户已解除冻结'
        WHEN 'RISK_LOGIN' THEN
            '检测到来自 ' || COALESCE(event.payload->>'loginAddr', '未知位置') || ' 的登录'
        WHEN 'SUPPORT_TICKET_COMPLETED' THEN
            '工单 ' || COALESCE(event.payload->>'ticketNo', '-') || ' 已处理完成'
        WHEN 'ORDER_PAYMENT_FAILED' THEN
            '金额 ' || COALESCE(event.payload->>'payMoney', '0') || ' 的支付未完成'
        WHEN 'ORDER_TIMED_OUT' THEN
            '订单 ' || COALESCE(event.payload->>'orderNo', '-') || ' 未在有效期内完成支付'
        WHEN 'PACKAGE_PAYMENT_SUCCEEDED' THEN
            '支付 ' || COALESCE(event.payload->>'payMoney', '0')
            || ' 成功，套餐有效期至 ' || COALESCE(event.payload->>'expireDate', '-')
        WHEN 'BALANCE_RECHARGE_SUCCEEDED' THEN
            '充值 ' || COALESCE(event.payload->>'payMoney', '0')
            || ' 成功，当前余额 ' || COALESCE(event.payload->>'balance', '0')
        WHEN 'REFUND_SUCCEEDED' THEN
            '退款 ' || COALESCE(event.payload->>'refundMoney', '0') || ' 已到账'
        WHEN 'PACKAGE_EXPIRES_IN_3_DAYS' THEN
            '您的套餐将于 ' || COALESCE(event.payload->>'expireDate', '-') || ' 到期'
        WHEN 'PACKAGE_EXPIRED' THEN '您的套餐已到期'
        WHEN 'API_RATE_LIMITED' THEN '检测到持续的 API 限流'
        ELSE notification.summary
    END
FROM platform.notification_event event
WHERE notification.event_id = event.id
  AND event.event_type IN (
      'WALLET_USAGE_SETTLED', 'WALLET_USAGE_DAILY_SUMMARY', 'BALANCE_LOW',
      'ACCOUNT_FROZEN', 'ACCOUNT_BANNED', 'ACCOUNT_UNFROZEN', 'RISK_LOGIN',
      'SUPPORT_TICKET_COMPLETED', 'ORDER_PAYMENT_FAILED', 'ORDER_TIMED_OUT',
      'PACKAGE_PAYMENT_SUCCEEDED', 'BALANCE_RECHARGE_SUCCEEDED', 'REFUND_SUCCEEDED',
      'PACKAGE_EXPIRES_IN_3_DAYS', 'PACKAGE_EXPIRED', 'API_RATE_LIMITED'
  );
