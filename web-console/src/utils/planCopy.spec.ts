import { describe, expect, it } from 'vitest'

import { userPlanDescription, userPlanName } from './planCopy'

describe('plan display copy', () => {
  it.each([
    ['FREE', 'Free', '免费版'],
    ['STARTER', 'Starter', '入门版'],
    ['ADVANCED', 'Advanced', '高级版'],
    ['ENTERPRISE', 'Enterprise', '企业版'],
  ])('maps built-in plan %s to Chinese', (code, fallback, expected) => {
    expect(userPlanName(code, fallback)).toBe(expected)
  })

  /**
   * 视频线的库内名称是英文（Video Starter / Video Creator / …），用户端套餐页必须给中文，
   * 否则同一个页面里积分套餐是中文、视频套餐是英文。年付版跟在月付版后面命名。
   */
  it.each([
    ['VIDEO_LITE', 'Video Lite', '视频轻量版'],
    ['VIDEO_LITE_ANNUAL', 'Video Lite Annual', '视频轻量版 · 年付'],
    ['VIDEO_STARTER', 'Video Starter', '视频基础版'],
    ['VIDEO_CREATOR', 'Video Creator', '视频创作版'],
    ['VIDEO_PRO', 'Video Pro', '视频专业版'],
  ])('maps the video plan %s to Chinese', (code, fallback, expected) => {
    expect(userPlanName(code, fallback)).toBe(expected)
  })

  it('maps the video plans\u2019 default descriptions and keeps administrator edits', () => {
    expect(userPlanDescription('VIDEO_STARTER', 'Entry video package: 60 seconds per month at 720P'))
      .toBe('每月 60 秒生成时长，480P / 720P / 1080P 三档可用。')
    expect(userPlanDescription('VIDEO_PRO', 'Professional video package: 600 seconds per month, priority queue'))
      .toBe('每月 600 秒生成时长，全部模型档位可用，含优先队列。')
    // 管理员在后台改了名字或说明就照实显示，不再套用内置文案
    expect(userPlanName('VIDEO_PRO', '导演版')).toBe('导演版')
    expect(userPlanDescription('VIDEO_PRO', '管理员自定义说明')).toBe('管理员自定义说明')
  })

  it('preserves administrator-defined future plans', () => {
    expect(userPlanName('CUSTOM', '专属套餐')).toBe('专属套餐')
    expect(userPlanDescription('CUSTOM', '专属说明')).toBe('专属说明')
  })

  it('preserves administrator changes to built-in plans', () => {
    expect(userPlanName('STARTER', '开发者套餐')).toBe('开发者套餐')
    expect(userPlanDescription('STARTER', '管理员自定义说明')).toBe('管理员自定义说明')
  })

  it('localizes only built-in default descriptions', () => {
    expect(userPlanDescription('FREE', 'One-time introductory package'))
      .toBe('适合首次体验，快速了解基础调用能力。')
  })
})
