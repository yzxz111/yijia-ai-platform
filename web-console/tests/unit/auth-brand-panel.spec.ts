import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import type { Component } from 'vue'
import { describe, expect, it } from 'vitest'

const componentModules = import.meta.glob('../../src/components/auth/AuthBrandPanel.vue')
const loginSource = readFileSync('src/views/auth/LoginView.vue', 'utf8')
const registerSource = readFileSync('src/views/auth/RegisterView.vue', 'utf8')

describe('authentication brand panel', () => {
  it('presents real platform capabilities without reference-project metrics', async () => {
    const loadComponent = componentModules['../../src/components/auth/AuthBrandPanel.vue']

    expect(loadComponent).toBeTypeOf('function')
    if (!loadComponent) return

    const component = await loadComponent() as { default: Component }
    const wrapper = mount(component.default, {
      props: { appName: '一加AI中台' },
      slots: { footer: '<a href="/login">返回账号登录</a>' },
    })

    expect(wrapper.get('.brand-name').text()).toBe('一加AI中台')
    expect(wrapper.get('.brand-heading').text()).toBe('让 AI 能力，触手可及。')
    expect(wrapper.find('.brand-mark').exists()).toBe(false)
    expect(wrapper.findAll('.auth-feature')).toHaveLength(3)
    expect(wrapper.findAll('.auth-summary-item')).toHaveLength(3)
    expect(wrapper.text()).toContain('统一管理模型接入')
    expect(wrapper.text()).toContain('集中维护 API 密钥')
    expect(wrapper.text()).toContain('查看调用与账单')
    expect(wrapper.text()).toContain('返回账号登录')
    expect(wrapper.text()).not.toMatch(/NexusAPI|24亿|1\.2万|48ms/)
  })

  it('keeps login and registration in the same product panel system', () => {
    for (const source of [loginSource, registerSource]) {
      expect(source).toMatch(
        /import AuthBrandPanel from ['"]\.\.\/\.\.\/components\/auth\/AuthBrandPanel\.vue['"]/,
      )
      expect(source).toMatch(/<AuthBrandPanel\s+:app-name="appName"/)
    }
  })
})
