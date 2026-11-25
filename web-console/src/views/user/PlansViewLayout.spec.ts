import { describe, expect, it } from 'vitest'

import plansSource from './PlansView.vue?raw'

describe('package card alignment', () => {
  it('reserves stable description and price rows across package cards', () => {
    expect(plansSource).toMatch(/\.plan-footer\s*\{[^}]*margin-top:\s*auto;/s)
    expect(plansSource).toMatch(/\.plan-price\s*\{[^}]*display:\s*flex;[^}]*align-items:\s*baseline;/s)
  })

  it('shows real quota, comparison, and FAQ sections alongside purchase actions', () => {
    expect(plansSource).toContain('class="plan-quota"')
    expect(plansSource).toContain('class="compare-table"')
    expect(plansSource).toContain('faqItems')
    expect(plansSource).toContain('data-test="buy-plan"')
    expect(plansSource).toContain('data-test="claim-free"')
  })})
