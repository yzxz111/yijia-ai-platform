import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const view = (name: string) => readFile(resolve(process.cwd(), 'src/views/admin', name), 'utf8')
const component = (name: string) => readFile(resolve(process.cwd(), 'src/components/admin', name), 'utf8')

describe('document five admin UI contracts', () => {
  it('uses styled communication filters and paginates broadcasts', async () => {
    const [communications, broadcasts] = await Promise.all([
      view('AdminCommunicationsView.vue'),
      component('BroadcastManager.vue'),
    ])

    expect(communications).toContain('<el-select v-model="broadcastStatus"')
    expect(communications).toContain('<el-select v-model="ticketStatus"')
    expect(communications).not.toContain('<select v-model="broadcastStatus"')
    expect([...communications.matchAll(/placeholder="全部状态"/g)]).toHaveLength(2)
    expect(communications).toContain('@page-change="changeBroadcastPage"')
    expect(broadcasts).toContain('<el-pagination')
    expect(broadcasts).toContain("'page-change': [page: number]")
    expect(broadcasts).not.toContain('收件人会在计划发送时冻结')
  })

  it('separates content safety records into distinct sections', async () => {
    const source = await view('ContentSafetyView.vue')

    expect(source).toContain('content-safety-sections')
    expect(source).toContain('data-test="safety-cases"')
    expect(source).toContain('data-test="safety-appeals"')
    expect(source).not.toContain('审核被拦截的请求、处理用户申诉')
    expect(source).not.toContain('fixed="right"')
  })

  it('removes the duplicate operations shortcut and localizes health states', async () => {
    const source = await view('OperationsView.vue')

    expect(source).not.toContain('to="/admin/communications"')
    expect(source).toContain("HEALTHY: '正常'")
    expect(source).toContain("UNHEALTHY: '异常'")
    expect(source).toContain('statusLabel(channel.lastHealthStatus)')
    expect(source).toContain('statusLabel(circuit.state)')
    expect(source).not.toContain('{{ channel.lastHealthStatus }}')
    expect(source).not.toContain('{{ circuit.state }}')
  })

  it('keeps operations health tags aligned with the metric row', async () => {
    const source = await view('OperationsView.vue')

    expect(source).toContain('min-height: 22px; align-items: center')
    expect(source).toContain('.operations-metrics .admin-metric .el-tag')
    expect(source).toContain('height: 22px; line-height: 20px')
  })

  it('enumerates audit filters and maps raw audit values', async () => {
    const source = await view('AuditView.vue')

    expect(source).toContain('auditActionOptions')
    expect(source).toContain('auditResourceTypeOptions')
    expect(source).toContain('<el-select v-model="action"')
    expect(source).toContain('<el-select v-model="resourceType"')
    expect(source).toContain('placeholder="全部结果"')
    expect(source).toContain('labelOf(actionLabels, entry.action)')
    expect(source).toContain('labelOf(resourceTypeLabels, entry.resourceType)')
    expect(source).toContain('labelOf(outcomeLabels, entry.outcome)')
    expect(source).toContain('entry.actorDisplayName')
    expect(source).toContain('entry.actorUserNo')
    expect(source).toContain('exportAudit')
    expect(source).toContain('aria-label="复制详情"')
    expect(source).toContain('layout="sizes, prev, pager, next"')
  })
})
