import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SkillAvatar from './SkillAvatar.vue'
import { defaultSkillLogoForCode } from '../../utils/skillDefaultLogos'

const fileManagerCode = 'russellfei-file-manager-b06ee3699daa'

describe('SkillAvatar', () => {
  it('prefers an uploaded icon, then the bundled logo, then its text badge after image errors', async () => {
    const wrapper = mount(SkillAvatar, {
      props: { src: 'blob:uploaded-icon', code: fileManagerCode, badge: '文', fallback: '件' },
    })
    expect(wrapper.get('img').attributes('src')).toBe('blob:uploaded-icon')

    await wrapper.get('img').trigger('error')
    expect(wrapper.get('img').attributes('src')).toBe(defaultSkillLogoForCode(fileManagerCode))

    await wrapper.get('img').trigger('error')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('文')

    await wrapper.setProps({ src: 'blob:replacement-icon' })
    expect(wrapper.get('img').attributes('src')).toBe('blob:replacement-icon')
  })

  it('uses the bundled logo when no upload exists and keeps unknown skills on text fallback', async () => {
    const wrapper = mount(SkillAvatar, { props: { code: fileManagerCode, badge: '文' } })
    expect(wrapper.get('img').attributes('src')).toBe(defaultSkillLogoForCode(fileManagerCode))

    await wrapper.setProps({ code: 'other-skill' })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('文')
  })
})
