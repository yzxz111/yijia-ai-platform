import type { PaymentProduct } from '../api/platform'

/** Payment products depend on the device, not the available window width. */
export function paymentProductForDevice(provider: string): PaymentProduct {
  const device = typeof navigator === 'undefined' ? undefined : navigator
  const mobile = Boolean(device && (
    /Android|iPhone|iPad|iPod|Mobile/i.test(device.userAgent)
    || (device.platform === 'MacIntel' && device.maxTouchPoints > 1)
  ))
  if (provider === 'WECHAT_PAY') return mobile ? 'WECHAT_H5' : 'WECHAT_NATIVE'
  if (provider === 'ALIPAY') return mobile ? 'ALIPAY_WAP' : 'ALIPAY_PAGE'
  return 'MOCK'
}
