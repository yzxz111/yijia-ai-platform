import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { isSecondDimension, quotaUnitLabel } from '../../api/platform'
import { isVideoModelCode, userModelLabel } from '../../utils/modelLabel'

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

/**
 * 视频套餐按秒而非按 token 计量。这些契约钉住那些
 * 否则会悄悄把秒数渲染成积分的点，
 * 从而错误陈述客户买到的东西。
 */
describe('video second package contracts', () => {
  it('maps each usage dimension to its quota unit', () => {
    expect(quotaUnitLabel('TOKEN')).toBe('积分')
    expect(quotaUnitLabel('VIDEO_SECOND')).toBe('秒')
    expect(quotaUnitLabel('IMAGE')).toBe('秒')
    expect(isSecondDimension('VIDEO_SECOND')).toBe(true)
    expect(isSecondDimension('TOKEN')).toBe(false)
    // 量纲缺失时必须回退为 token：所有既有套餐都不带该字段。
    expect(quotaUnitLabel(undefined)).toBe('积分')
    expect(isSecondDimension(undefined)).toBe(false)
  })

  it('labels video aliases and recognises them by code', () => {
    expect(userModelLabel('video-basic')).toBe('视频模型 · 720P')
    expect(userModelLabel('video-max')).toBe('视频模型 · 4K')
    expect(isVideoModelCode('video-standard')).toBe(true)
    expect(isVideoModelCode('text-basic')).toBe(false)
  })

  it('labels the plan card quota from the plan dimension', async () => {
    const view = await source('src/views/user/PlansView.vue')

    expect(view).toContain('quotaUnitLabel(plan.usageDimension)')
    expect(view).toContain('isSecondDimension(plan.usageDimension)')
    // 按秒计费的套餐不得用 TPM 上限来描述。
    expect(view).toContain("unit === '秒' ? 'SPM' : 'TPM'")
  })

  it('labels the checkout and order quota from the snapshot dimension', async () => {
    const checkout = await source('src/components/payment/CheckoutDialog.vue')
    const orders = await source('src/views/user/OrdersView.vue')

    expect(checkout).toContain('quotaUnitLabel(displayedDimension.value)')
    expect(checkout).toContain('packageSnapshot.usageDimension')
    expect(orders).toContain('quotaUnitLabel(selectedDetail.value?.packageSnapshot.usageDimension)')
  })

  it('lets administrators set the VIDEO capability on a model', async () => {
    const view = await source('src/views/admin/ModelsView.vue')

    // 没有这个复选框，即便两个后端白名单都接受 VIDEO，
    // 也无法从控制台创建视频别名。
    expect(view).toContain('<el-checkbox value="VIDEO">视频</el-checkbox>')
    expect(view).toContain("VIDEO: '视频'")
  })
})
