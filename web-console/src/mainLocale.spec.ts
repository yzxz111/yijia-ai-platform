import { describe, expect, it } from 'vitest'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import mainSource from './main.ts?raw'

describe('Element Plus locale', () => {
  it('uses the Chinese locale for global components such as pagination', () => {
    expect(zhCn.el.pagination.pagesize).toBe('条/页')
    expect(mainSource).toContain("import zhCn from 'element-plus/es/locale/lang/zh-cn'")
    expect(mainSource).toMatch(/\.use\(ElementPlus,\s*\{\s*locale:\s*zhCn\s*\}\)/)
  })
})
