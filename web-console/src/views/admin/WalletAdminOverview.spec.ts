import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WalletAdminView from './WalletAdminView.vue'
import * as wallet from '../../api/wallet'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('../../api/admin', () => ({ listAudit: vi.fn() }))
vi.mock('../../api/wallet', () => ({
  getAdminWalletOverview: vi.fn(), listAdminWalletAccounts: vi.fn(), listAdminWalletLedger: vi.fn(),
  listAdminWalletRecharges: vi.fn(), listAdminWalletRechargeOptions: vi.fn(), replaceAdminWalletRechargeOptions: vi.fn(),
}))

function mountView() {
  return mount(WalletAdminView, { global: { stubs: {
    AppChart: { name: 'AppChart', props: ['option'], template: '<div data-test="chart" />' },
    'el-pagination': true, 'el-dialog': true, 'el-drawer': true, 'el-tag': true,
    TopErrorMessage: { props: ['message'], template: '<div>{{ message }}</div>' },
  } } })
}

describe('admin wallet overview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const page = { items: [], total: 0, page: 0, size: 20 }
    vi.mocked(wallet.listAdminWalletAccounts).mockResolvedValue(page)
    vi.mocked(wallet.listAdminWalletLedger).mockResolvedValue(page)
    vi.mocked(wallet.listAdminWalletRecharges).mockResolvedValue(page)
    vi.mocked(wallet.getAdminWalletOverview).mockResolvedValue({
      accounts: 150, totalBalance: 123456, todayRecharge: 100, todayDebit: 1,
      timezone: 'Asia/Shanghai', trend: [{ date: '2026-09-13', recharge: 100, debit: 1 }],
    })
  })

  it('shows server totals independently of the filtered page and retains cents in the chart', async () => {
    const wrapper = mountView()
    await flushPromises()
    const stats = wrapper.find('.wallet-stats').text()
    expect(stats).toContain('150')
    expect(stats).toContain('¥1,234.56')
    expect(stats).toContain('¥1.00')
    expect(stats).toContain('¥0.01')
    expect(wrapper.find('[data-test="accounts-empty"]').exists()).toBe(true)
    const chart = wrapper.findComponent({ name: 'AppChart' })
    const option = chart.props('option')
    expect(option.series[1].data).toEqual([0.01])
  })

  it('shows an error and retry instead of false zero totals when overview fails', async () => {
    vi.mocked(wallet.getAdminWalletOverview).mockRejectedValueOnce(new Error('统计暂时不可用'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('统计暂时不可用')
    expect(wrapper.find('.wallet-stats').text()).toContain('—')
    const retry = wrapper.findAll('button').find(button => button.text() === '重试钱包统计')!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('.wallet-stats').text()).toContain('¥1,234.56')
  })
})
