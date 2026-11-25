import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

/**
 * 管理端的「视频计费」契约。
 *
 * 视频别名要卖得出去，除了渠道和密钥还需要三样东西，缺任何一样都是一个静默的坑：
 * 网关的分辨率档位（缺 → 调用 422）、网关的每秒成本/零售价（缺 → 平台没有定价依据）、
 * 平台侧的按秒登记（缺 → 平台把别名当积分别名，套餐发布后到发放时才被拒）。
 * 这几条断言把三个入口和保存链路钉在页面上，免得以后重构时又只剩渠道配置那一块。
 */
describe('admin video billing contract', () => {
  it('shows the video billing slots only when the model has the video capability', async () => {
    const view = await source('src/views/admin/ModelsView.vue')

    expect(view).toContain('v-if="isVideoModel"')
    expect(view).toContain("form.capabilities.includes('VIDEO')")
    expect(view).toContain('data-test="model-video-tier"')
    expect(view).toContain('data-test="model-video-cost"')
    expect(view).toContain('data-test="model-video-price"')
    expect(view).toContain('data-test="model-video-retry"')
  })

  it('refuses a retail price below the margin floor before saving anything', async () => {
    const view = await source('src/views/admin/ModelsView.vue')

    // 页面提示与网关校验用同一个公式，口径不能有第二套
    expect(view).toContain('Math.ceil(form.videoProviderUnitCost / 0.7)')
    expect(view).toContain('form.videoUnitPrice < videoPriceFloor.value')
    expect(view).toContain('30% 毛利地板')
  })

  it('writes the gateway tier, the gateway rate card and the platform dimension', async () => {
    const view = await source('src/views/admin/ModelsView.vue')
    const api = await source('src/api/admin.ts')

    expect(view).toContain('saveVideoAliasBilling(')
    expect(view).toContain('registerModelDimension(')
    expect(view).toContain("usageDimension: 'VIDEO_SECOND'")
    // 编辑已有别名时带出当前值，改成本不用再写迁移
    expect(view).toContain('applyVideoBilling(')

    expect(api).toContain('/console/gateway/admin/video-aliases')
    expect(api).toContain('/console/platform/admin/model-dimensions/')
  })
})
