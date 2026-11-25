import { describe, expect, it } from 'vitest'

import {
  SKILL_ERROR_MESSAGES,
  SKILL_NETWORK_MESSAGE,
  skillErrorMessage,
  skillFailure,
} from './skillErrors'

function apiError(code: string, status: number, requestId = 'req_123') {
  return {
    response: {
      status,
      headers: { 'x-request-id': requestId },
      data: {
        error: { message: `raw upstream message for ${code}`, type: 'invalid_request_error', code },
        request_id: requestId,
      },
    },
  }
}

describe('skillFailure', () => {
  it('maps every documented error code to stable Chinese copy', () => {
    const expected = {
      invalid_skill_name: 'Skill 名称不符合命名规则。',
      skill_not_found: 'Skill 不存在或已下架。',
      invalid_skill_metadata: 'Skill 元数据不合法，请联系管理员检查 skill.json。',
      skill_file_too_large: 'Skill 内单个文件超出大小限制。',
      skill_too_large: 'Skill 超出大小限制。',
      skill_too_many_files: 'Skill 文件数量超出限制。',
      skill_storage_unavailable: 'Skill 存储暂时不可用，请稍后重试。',
      // 专家化改造（设计 2026-09-14 补充 §3.1）新增的 6 个码同样可能出现在只读通道上，
      // 文案是面向客户端目录的简版；管理面另有更具体的措辞（skillAdminErrors.ts）。
      invalid_skill_kind: 'Skill 类型不合法。',
      invalid_expert_manifest: 'Skill 包内的 plugin.json 不合法。',
      expert_kind_mismatch: 'Skill 包内的 expertType 与技能类型不一致。',
      invalid_skill_member: 'Skill 花名册不合法：成员标识不能重复，且最多一位主理人。',
      skill_member_not_found: 'Skill 成员不存在或已被移除。',
      skill_illustration_not_found: 'Skill 没有介绍插画。',
      skill_illustration_too_large: '介绍插画不能超过 4 MB。',
      skill_example_image_not_found: 'Skill 没有示例图片。',
      skill_example_image_too_large: '示例图片不能超过 4 MB。',
    }

    expect(SKILL_ERROR_MESSAGES).toEqual(expected)
    for (const [code, message] of Object.entries(expected)) {
      const failure = skillFailure(apiError(code, 422))
      expect(failure.code, code).toBe(code)
      expect(failure.message, code).toBe(message)
      expect(failure.message, code).not.toContain('raw upstream message')
    }
  })

  it('keeps the request id from the body and falls back to the x-request-id header', () => {
    expect(skillFailure(apiError('skill_not_found', 404, 'req_body')).requestId).toBe('req_body')

    const headerOnly = {
      response: {
        status: 503,
        headers: { 'x-request-id': 'req_header' },
        data: { error: { code: 'skill_storage_unavailable', message: 'io' } },
      },
    }
    expect(skillFailure(headerOnly).requestId).toBe('req_header')
  })

  it('falls back to status copy when the code is unknown', () => {
    expect(skillFailure(apiError('brand_new_code', 401)).message).toBe('登录状态已失效，请重新登录后再试。')
    expect(skillFailure({ response: { status: 422, data: {} } }).message).toBe('Skill 内容不符合接口要求。')
    expect(skillFailure({ response: { status: 500, data: {} } }).message)
      .toBe('Skill 服务暂时不可用，请稍后重试。')
  })

  it('treats a body-less console 401 as a lost login session', () => {
    const failure = skillFailure({ response: { status: 401, headers: {}, data: '' } })

    expect(failure.code).toBe('')
    expect(failure.message).toBe('登录状态已失效，请重新登录后再试。')
  })

  it('reports a network failure when there is no response at all', () => {
    expect(skillErrorMessage(new Error('Network Error'))).toEqual({
      code: '',
      message: SKILL_NETWORK_MESSAGE,
      requestId: '',
    })
  })

  it('never surfaces an unstructured server message', () => {
    const failure = skillErrorMessage({
      response: { status: 500, headers: {}, data: { message: 'duplicate key value violates constraint' } },
    })

    expect(failure.message).toBe('Skill 服务暂时不可用，请稍后重试。')
    expect(failure.message).not.toContain('duplicate key')
  })
})


