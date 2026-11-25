import { describe, expect, it } from 'vitest'

import coordinatorSource from './TerminalSessionCoordinator.vue?raw'

describe('terminal session coordinator', () => {
  it('requests delayed session closure for a real page unload and ignores bfcache', () => {
    expect(coordinatorSource).toContain("window.addEventListener('pagehide', onPageHide)")
    expect(coordinatorSource).toContain('if (!event.persisted) closeBrowserPage()')
    expect(coordinatorSource).toContain("window.removeEventListener('pagehide', onPageHide)")
  })
})
