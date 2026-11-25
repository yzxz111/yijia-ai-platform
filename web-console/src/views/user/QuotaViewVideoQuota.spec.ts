import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

/**
 * 「积分额度」页对视频套餐的处理。
 *
 * 网关额度接口返回的 totalTokens / usedTokens / remainingTokens 对视频套餐来说是**秒**，
 * 而用户端不展示视频套餐的秒数额度（套餐页只写"按秒计费"）。所以这一页必须按网关带回来的
 * usageDimension 分流：视频套餐下整块数字区（指标卡、按模型占比）都不渲染，改成口径说明。
 * 判定绝不能用套餐代码硬编码——管理员可以新建套餐，代码不是稳定的类型判据。
 */
describe('video quota page contract', () => {
  it('按网关的 usageDimension 判断，而不是猜套餐代码', async () => {
    const view = await source('src/views/user/QuotaView.vue')
    const api = await source('src/api/gateway.ts')

    expect(view).toContain("dimension === 'VIDEO_SECOND'")
    expect(view).toContain("dimension === 'IMAGE'")
    expect(api).toContain('usageDimension?')
  })

  it('视频套餐下不渲染任何额度数字', async () => {
    const view = await source('src/views/user/QuotaView.vue')

    expect(view).toContain('v-if="!quotaIsSeconds" class="metric-grid"')
    expect(view).toContain('v-if="!quotaIsSeconds" class="charts-section"')
    expect(view).toContain('v-if="showAlertBanner && !quotaIsSeconds"')
    expect(view).toContain('data-test="video-quota-note"')
    // 标题与按钮也换口径，否则页面一边说"积分额度管理"一边不显示任何数字
    expect(view).toContain("quotaIsSeconds.value ? '视频额度管理' : '积分额度管理'")
    // 混合套餐（视频秒 + 音频秒）多展示一块语音额度，标题与副标题也必须跟着改，
    // 否则页面一边列着语音秒、一边只声明自己管视频额度。
    expect(view).toContain("'视频与语音额度管理'")
    expect(view).toContain("'视频按生成时长、语音按处理时长分别扣减 · 两份额度互不挤占'")
    expect(view).toContain("quotaIsSeconds ? '购买视频套餐' : '购买积分'")
  })

  it('额度页与个人中心的套餐名走中文映射', async () => {
    expect(await source('src/views/user/QuotaView.vue')).toContain('userPlanName(')
    expect(await source('src/views/user/ProfileView.vue')).toContain('userPlanName(')
  })
})
