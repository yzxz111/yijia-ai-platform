import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFile(resolve(process.cwd(), path), 'utf8')

/**
 * 调用日志页对视频调用的显示。
 *
 * 视频生成在网关里按秒结算：usage_log 的 input/output/total_tokens 按约束全为 0，
 * 真正计费的是 usage_units（已计费秒数），单位记在 usage_dimension 上。页面只看 token
 * 计数就会把一次生成显示成"消耗 0 积分"。列表、详情、CSV 导出三处都要按维度分流。
 */
describe('usage log contract for second-billed calls', () => {
  it('按 usageDimension 分流，并读 usageUnits 而不是 totalTokens', async () => {
    const view = await source('src/views/user/UsageLogsView.vue')
    const api = await source('src/api/gateway.ts')

    expect(view).toContain("item.usageDimension === 'VIDEO_SECOND'")
    expect(view).toContain('item.usageUnits ?? 0')
    expect(api).toContain('usageUnits?')
    expect(api).toContain('usageDimension?')
  })

  it('列表、详情与导出都不再把视频调用写成积分数', async () => {
    const view = await source('src/views/user/UsageLogsView.vue')

    // 列名中性化，只有积分调用才渲染 token 计数
    expect(view).toContain('<th>总用量</th>')
    expect(view).not.toContain('<th>总积分</th>')
    expect(view).toContain('{{ usageAmountLabel(item) }}')
    expect(view).toContain('{{ isSecondUsage(item) ? \'—\' : number.format(item.inputTokens) }}')
    // 详情：视频调用显示生成时长，积分明细整块不渲染
    expect(view).toContain('<dt>生成时长</dt>')
    expect(view).toContain('<template v-if="isSecondUsage(selectedUsage)">')
    // 导出：混合维度必须单列"计量单位"，否则一列里同时躺着积分和秒
    expect(view).toContain("'计量单位'")
  })
})
