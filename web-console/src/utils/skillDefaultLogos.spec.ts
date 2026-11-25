import { describe, expect, it } from 'vitest'
import { defaultSkillLogoForCode } from './skillDefaultLogos'

describe('defaultSkillLogoForCode', () => {
  it('resolves exactly the eleven catalog codes and their source slugs', () => {
    const identities = [
      ['russellfei-file-manager-b06ee3699daa', 'file-manager'],
      ['matagul-desktop-control-891a9b41e92e', 'desktop-control'],
      ['kkkkhazix-aihot-d1f2b4714045', 'aihot'],
      ['maddiedreese-figma-deffbc10ec73', 'figma'],
      ['jk-automation-workflows-b444b6f0a8a5', 'automation-workflows'],
      ['jacky1n7-openclaw-tavily-search-5f8a7e9718f1', 'openclaw-tavily-search'],
      ['arminnaimi-agent-team-orchestration-63936a511529', 'agent-team-orchestration'],
      ['clawhub-daily-trending-39aee037ebbd', 'daily-trending'],
      ['jackeven02-document-pro-fc17dd61013e', 'document-pro'],
      ['gongyu0918-debug-chinese-official-writing-601f63b1c6a2', 'debug-chinese-official-writing'],
      ['content-writer', 'content-writer'],
    ] as const

    const logos = identities.map(([code, slug]) => {
      const logo = defaultSkillLogoForCode(code)
      expect(logo).not.toBe('')
      expect(defaultSkillLogoForCode(slug)).toBe(logo)
      return logo
    })
    expect(new Set(logos).size).toBe(11)
  })

  it('leaves unknown and partial codes on the existing text fallback', () => {
    expect(defaultSkillLogoForCode('another-file-manager')).toBe('')
    expect(defaultSkillLogoForCode('russellfei-file-manager')).toBe('')
    expect(defaultSkillLogoForCode('gongyu0918-debug-chinese-official-writing')).toBe('')
    expect(defaultSkillLogoForCode('unknown')).toBe('')
    expect(defaultSkillLogoForCode('constructor')).toBe('')
    expect(defaultSkillLogoForCode('__proto__')).toBe('')
    expect(defaultSkillLogoForCode('toString')).toBe('')
    expect(defaultSkillLogoForCode(null)).toBe('')
  })
})
