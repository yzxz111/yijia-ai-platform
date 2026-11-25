import { describe, expect, it } from 'vitest'

import walletAdminSource from './WalletAdminView.vue?raw'

describe('WalletAdminView filter sizing', () => {
  it('keeps the desktop filter actions equal-width and mobile actions full-width', () => {
    expect(walletAdminSource).toMatch(
      /\.filters > button\s*\{[^}]*width:\s*76px;[^}]*min-width:\s*76px;/s,
    )
    expect(walletAdminSource).toMatch(
      /@media \(max-width: 720px\)[^{]*\{[\s\S]*?\.filters label, \.filters button \{ width: 100%; \}/,
    )
  })
})
