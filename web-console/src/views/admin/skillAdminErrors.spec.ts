import { describe, expect, it } from 'vitest'

import { SKILL_ERROR_MESSAGES } from './skillErrors'
import {
  SKILL_ADMIN_ERROR_MESSAGES,
  SKILL_ADMIN_NETWORK_MESSAGE,
  skillAdminErrorMessage,
  skillAdminFailure,
} from './skillAdminErrors'

/** 管理面是扁平信封 `{code,message}`，不是 OpenAI 信封 */
function apiError(code: string, status: number, message = `raw upstream message for ${code}`) {
  return { response: { status, data: { code, message } } }
}

const DOCUMENTED: Record<string, string> = {
  skill_code_conflict: '该技能标识已被占用，请换一个。',
  skill_version_conflict: '该版本号已存在，或正被其他上传占用，请刷新后重试。',
  skill_cannot_delete_published: '已上架的技能不能删除，请先下架。',
  skill_has_no_ready_version: '该技能还没有可发布（已就绪）的版本，请先上传技能包。',
  skill_concurrent_modification: '内容已被他人修改，请关闭后重新打开。',
  skill_zip_invalid: '技能包结构校验未通过：请确认上传的是 YCPS 包（.ycps.zip），根目录包含 plugin.json，入口技能位于 skills/<code>/SKILL.md。',
  skill_zip_unsafe_entry: '技能包包含不安全的条目（如 ..、绝对路径或符号链接），已拒绝。',
  skill_manifest_mismatch: '技能包内 plugin.json 的 code 与当前技能标识不一致。',
  skill_package_too_large: '技能包超出大小上限：YCPS 压缩包按类型限制（技能 10 MB、专家 2 MB、专家团 5 MB），全局上限 200 MB。',
  skill_package_rejected: '技能包未通过内容安全扫描，已拒绝入库。',
  skill_scan_unavailable: '内容安全扫描服务暂时不可用，请稍后重试。',
  skill_scan_rejected: '技能包未通过安全扫描，已拒绝。',
  skill_storage_unavailable: '技能存储暂时不可用，请稍后重试。',
  skill_restore_expired: '该记录已超过 30 天恢复期限，无法恢复。',
  skill_recycle_delete_blocked: '仍有进行中的下载，暂时无法彻底删除。',
  invalid_pagination: '分页参数不合法：页码从 0 开始，每页最多 100 条。',
  invalid_skill_name: '技能标识不符合命名规则：只能由小写字母和数字组成，单词之间用连字符（-）分隔，例如 finance-analyst。',
  rate_limited: '操作过于频繁，请稍后再试。',
}

describe('skillAdminFailure', () => {
  it('maps every documented admin error code to stable Chinese copy', () => {
    for (const [code, message] of Object.entries(DOCUMENTED)) {
      expect(SKILL_ADMIN_ERROR_MESSAGES[code], code).toBe(message)
      const failure = skillAdminFailure(apiError(code, 409))
      expect(failure.code, code).toBe(code)
      expect(failure.message, code).toBe(message)
      expect(failure.message, code).not.toContain('raw upstream message')
    }
  })

  it('never surfaces the server message when the code is unmapped', () => {
    const failure = skillAdminFailure(apiError('brand_new_code', 409, 'duplicate key value violates constraint'))

    expect(failure.code).toBe('brand_new_code')
    expect(failure.message).toBe('当前状态不允许该操作，请刷新列表后重试。')
    expect(failure.message).not.toContain('duplicate key')
  })

  it('explains missing ClawHub version selection and a broken remembered release', () => {
    expect(skillAdminErrorMessage(apiError('native_clawhub_version_id_required', 409)))
      .toContain('请在详情中明确选择一个版本')
    expect(skillAdminErrorMessage(apiError('native_clawhub_release_conflict', 409)))
      .toBe(SKILL_ADMIN_ERROR_MESSAGES.native_clawhub_release_conflict)
    expect(skillAdminErrorMessage(apiError('native_clawhub_release_conflict', 409)))
      .not.toContain('当前状态不允许该操作')
  })

  it('gives actionable Chinese errors for remote fetch and commit failures', () => {
    const cases = [
      ['remote_skill_not_found', 404, '远程技能、仓库或指定版本不存在'],
      ['remote_skill_rate_limited', 429, '稍后重新拉取'],
      ['remote_skill_document_ambiguous', 422, '具体技能目录链接'],
      ['github_tree_path_invalid', 422, '技能目录不存在'],
      ['clawhub_import_metadata_invalid', 422, '检查名称、介绍和来源信息'],
    ] as const
    for (const [code, status, phrase] of cases) {
      const message = skillAdminErrorMessage(apiError(code, status))
      expect(message, code).toContain(phrase)
      expect(message, code).not.toContain('raw upstream message')
    }
  })

  it('covers the whole §5.1 / §9.5 documented code set', () => {
    for (const code of [
      'skill_code_conflict',
      'skill_version_conflict',
      'skill_cannot_delete_published',
      'skill_has_no_ready_version',
      'skill_concurrent_modification',
      'skill_zip_invalid',
      'skill_zip_unsafe_entry',
      'skill_manifest_mismatch',
      'skill_package_too_large',
      'skill_package_mismatch',
      'skill_package_rejected',
      'skill_scan_unavailable',
      'skill_scan_rejected',
      'skill_scan_failed',
      'skill_version_published',
      'skill_version_online',
      'skill_version_has_active_downloads',
      'skill_version_not_found',
      'skill_icon_invalid',
      'skill_icon_too_large',
      'skill_storage_unavailable',
      'skill_unpublished',
      'skill_not_found',
      'invalid_skill_name',
      'invalid_skill_metadata',
      'invalid_pagination',
      'skill_file_too_large',
      'skill_too_large',
      'skill_too_many_files',
      'idempotency_key_reused',
      'client_version_too_low',
      'rate_limited',
      'download_session_expired',
      'download_not_found',
      'account_safety_restricted',
      'content_safety_unavailable',
    ]) {
      expect(SKILL_ADMIN_ERROR_MESSAGES[code], code).toBeTruthy()
    }
  })

  it('maps the YCPS intake, sealed-version and dependency-closure codes used by the review surface', () => {
    const codes = [
      'ycps_package_filename_required',
      'ycps_package_invalid',
      'package_identity_mismatch',
      'ycps_package_unsafe_entry',
      'ycps_package_too_large',
      'ycps_dependency_unresolved',
      'skill_version_sealed',
      'skill_version_not_publishable',
      'skill_version_has_dependents',
      'skill_has_active_dependents',
      'skill_dependency_cycle',
      'skill_dependency_depth_exceeded',
      'skill_dependency_count_exceeded',
      'skill_dependency_lock_conflict',
      'skill_icon_not_found',
      'authentication_required',
    ]
    for (const code of codes) {
      const failure = skillAdminFailure(apiError(code, 409))
      expect(failure.code, code).toBe(code)
      expect(failure.message, code).toBe(SKILL_ADMIN_ERROR_MESSAGES[code])
      expect(failure.message, code).not.toContain('raw upstream message')
    }
  })

  it('falls back to status copy for a bodiless envelope', () => {
    expect(skillAdminFailure({ response: { status: 403, data: '' } }).message)
      .toBe('当前账号没有管理 Skill 的权限。')
    expect(skillAdminFailure({ response: { status: 409, data: {} } }).message)
      .toBe('当前状态不允许该操作，请刷新列表后重试。')
    expect(skillAdminFailure({ response: { status: 413, data: {} } }).message)
      .toBe('技能包超出大小上限：YCPS 压缩包按类型限制（技能 10 MB、专家 2 MB、专家团 5 MB），全局上限 200 MB。')
    expect(skillAdminFailure({ response: { status: 422, data: {} } }).message)
      .toBe('提交的内容不符合接口要求。')
  })

  it('reports a network failure when there is no response at all', () => {
    expect(skillAdminErrorMessage(new Error('Network Error'))).toBe(SKILL_ADMIN_NETWORK_MESSAGE)
    expect(SKILL_ADMIN_NETWORK_MESSAGE).toContain('无法连接到 Skill 管理服务')
    expect(skillAdminFailure(new Error('Network Error'))).toEqual({
      code: '',
      message: SKILL_ADMIN_NETWORK_MESSAGE,
    })
  })

  describe('专家聚合提交', () => {
    const expertScope = { noun: '专家', packageNoun: '专家包' }
    const withField = (code: string, field: unknown, status = 422) => ({
      response: { status, data: {
        code, message: 'database connection string and internal object key', details: { field },
      } },
    })

    it('分别解释图片大小、格式和尺寸错误', () => {
      for (const [code, field, expected] of [
        ['expert_image_too_large_icon', 'images.icon', '128 KiB'],
        ['expert_image_too_large_illustration', 'images.illustration', '640 KiB'],
        ['expert_image_too_large_exampleImage', 'images.exampleImage', '512 KiB'],
        ['invalid_expert_image_icon_icon_dimensions', 'images.icon', '正方形'],
        ['invalid_expert_image_illustration_illustration_dimensions', 'images.illustration', '3:1'],
        ['invalid_expert_image_exampleImage_example_dimensions', 'images.exampleImage', '1440 × 1080'],
        ['invalid_expert_image_icon_type', 'images.icon', 'PNG、JPEG 或 WebP'],
        ['expert_images_total_too_large', 'images', '1.25 MiB'],
      ]) {
        const message = skillAdminErrorMessage(withField(code, field), expertScope)
        expect(message, code).toContain(expected)
        expect(message, code).toContain('问题位置：')
        expect(message, code).not.toContain('database connection')
      }
    })

    it('覆盖每个图片角色的文件、动作、内容与尺寸错误码', () => {
      for (const role of ['icon', 'illustration', 'exampleImage']) {
        for (const code of [
          `expert_image_file_required_${role}`,
          `expert_image_missing_${role}`,
          `expert_image_empty_${role}`,
          `expert_image_too_large_${role}`,
          `invalid_expert_image_action_${role}`,
          ...['empty', 'type', 'magic', 'type_magic_mismatch', 'role', 'dimensions',
            'png_header', 'jpeg_header', 'webp_size', 'webp_header']
            .map((reason) => `invalid_expert_image_${role}_${reason}`),
        ]) {
          expect(skillAdminErrorMessage(apiError(code, 422), expertScope), code)
            .not.toBe('提交的内容不符合接口要求。')
        }
      }
    })

    it('基础字段、展示文案与便携包错误不会退化成笼统的 422 提示', () => {
      for (const code of [
        'expert_aggregate_json_invalid', 'expected_row_version_invalid',
        'skill_code_required', 'invalid_skill_code', 'skill_name_invalid',
        'skill_category_invalid', 'skill_tags_invalid', 'skill_summary_invalid',
        'skill_summary_en_invalid', 'skill_description_invalid',
        'skill_description_en_invalid', 'skill_min_client_version_invalid',
        'skill_expert_profile_task_types_invalid', 'skill_expert_profile_usage_guide_invalid',
        'skill_expert_profile_prompts_invalid', 'skill_expert_profile_preview_invalid',
        'skill_expert_profile_canvas_invalid', 'skill_expert_profile_canvas_too_large',
        'expert_presentation_asset_invalid', 'expert_presentation_asset_too_large',
        'expert_presentation_catalog_copy_invalid',
      ]) {
        const message = skillAdminErrorMessage(apiError(code, 422), expertScope)
        expect(message, code).not.toBe('提交的内容不符合接口要求。')
        expect(message, code).not.toContain('raw upstream message')
      }
    })

    it('标出安全字段名，处理预览和画布的嵌套路径', () => {
      expect(skillAdminErrorMessage(withField('skill_summary_en_invalid', 'summaryEn'), expertScope))
        .toBe('问题位置：英文卡片摘要。英文卡片摘要不能超过 512 个字符。')
      expect(skillAdminErrorMessage(withField('skill_expert_profile_preview_invalid', 'preview.table.rows[2]'), expertScope))
        .toContain('问题位置：中文预览表格。')
      expect(skillAdminErrorMessage(withField('skill_expert_profile_canvas_invalid', 'canvas.en.nodes[0]'), expertScope))
        .toContain('问题位置：英文展示画布第 1 个区块。')
      expect(skillAdminErrorMessage(withField('skill_expert_profile_canvas_invalid', 'canvas.zh.nodes[0].table'), expertScope))
        .toContain('问题位置：中文展示画布第 1 个区块的表格。')
      expect(skillAdminErrorMessage(withField('skill_expert_profile_canvas_invalid', 'canvas.zh.nodes[1].runs[2]'), expertScope))
        .toContain('问题位置：中文展示画布第 2 个区块的富文本。')
    })

    it('未知 code 或不在白名单中的 field 不会回显服务端内容', () => {
      expect(skillAdminErrorMessage(withField('unknown_expert_error', 'summaryEn'), expertScope))
        .toBe('提交的内容不符合接口要求。')
      expect(skillAdminErrorMessage(withField('skill_summary_invalid', 'secret.connection'), expertScope))
        .toBe('中文卡片摘要不能超过 512 个字符。')
      expect(skillAdminErrorMessage(withField('skill_summary_invalid', '<script>alert(1)</script>'), expertScope))
        .not.toContain('<script>')
      expect(skillAdminErrorMessage(apiError('__proto__', 422, 'secret token'), expertScope))
        .toBe('提交的内容不符合接口要求。')
      expect(skillAdminErrorMessage(withField('skill_summary_invalid', 'summary'), undefined))
        .toBe('提交的内容不符合接口要求。')
      expect(skillAdminErrorMessage(withField('skill_summary_invalid', 'summary'), {
        noun: '专家团', packageNoun: '专家团包',
      })).toBe('提交的内容不符合接口要求。')
    })

    it('专家的 skill_icon_invalid 解释为徽标文本，普通技能沿用原文案', () => {
      expect(skillAdminErrorMessage(withField('skill_icon_invalid', 'icon'), expertScope))
        .toBe('问题位置：徽标文本。徽标文本不能超过 8 个字符。')
      expect(skillAdminErrorMessage(apiError('skill_icon_invalid', 422)))
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_icon_invalid)
      expect(skillAdminErrorMessage(apiError('skill_name_invalid', 422)))
        .toBe('提交的内容不符合接口要求。')
    })
  })

  /**
   * 编码在普通技能、专家与专家团之间共用一个命名空间，所以撞名必须说明占用者是谁；
   * 否则管理员无法判断是自己选错了页面还是真的撞码。
   */
  describe('编码撞名（A22 返回 details.occupiedBy）', () => {
    const occupied = (kind: string, code = 'content-writer') => ({
      response: {
        status: 409,
        data: { code: 'skill_code_conflict', message: '该编码已被占用。', details: { code, occupiedBy: { id: 'x', code, kind, status: 'PUBLISHED' } } },
      },
    })

    it('names the holder kind instead of the generic copy', () => {
      expect(skillAdminFailure(occupied('SKILL')).message)
        .toBe('编码「content-writer」已被一个普通技能占用。编码在普通技能、专家与专家团之间全局唯一且不可复用，请换一个编码。')
      expect(skillAdminFailure(occupied('EXPERT')).message).toContain('已被一个专家占用')
      expect(skillAdminFailure(occupied('TEAM')).message).toContain('已被一个专家团占用')
    })

    it('keeps the generic copy when an older backend sends no holder', () => {
      expect(skillAdminFailure(apiError('skill_code_conflict', 409)).message)
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_code_conflict)
      expect(skillAdminFailure({ response: { status: 409, data: { code: 'skill_code_conflict', details: {} } } }).message)
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_code_conflict)
    })

    it('does not localize the holder sentence away on the expert page', () => {
      // 占用者类型是事实描述（可能是普通技能），不能被页面作用域替换成「专家」。
      expect(skillAdminFailure(occupied('SKILL'), { noun: '专家', packageNoun: '专家包' }).message)
        .toContain('已被一个普通技能占用')
    })
  })

  /**
   * 删除分类被拒时，只说「仍在使用」管理员不知道该改哪一个包，
   * 所以 A24 会带上分类名、占用数量与前几个包标识，这里把它们渲染成可操作的提示。
   */
  describe('分类占用（A24 返回 details.packages）', () => {
    const inUse = (details: unknown) => ({
      response: { status: 409, data: { code: 'skill_category_in_use', message: 'server copy', details } },
    })

    it('names the packages still holding the category', () => {
      const message = skillAdminFailure(inUse({
        kind: 'EXPERT', name: '内容创作', usageCount: 3, packages: ['pet-care', 'lesson-planner'],
      })).message
      expect(message).toContain('内容创作')
      expect(message).toContain('3 个包')
      expect(message).toContain('pet-care')
      expect(message).toContain('lesson-planner')
      expect(message).toContain('等')
    })

    it('omits the trailing 等 when every package is already listed', () => {
      const message = skillAdminFailure(inUse({
        kind: 'SKILL', name: '文档与数据', usageCount: 1, packages: ['xlsx'],
      })).message
      expect(message).toContain('xlsx')
      expect(message).not.toContain('等')
    })

    it('falls back to the generic copy when details are missing or malformed', () => {
      expect(skillAdminFailure(apiError('skill_category_in_use', 409)).message)
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_category_in_use)
      expect(skillAdminFailure(inUse({ name: '', usageCount: 0 })).message)
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_category_in_use)
      expect(skillAdminFailure(inUse(null)).message)
        .toBe(SKILL_ADMIN_ERROR_MESSAGES.skill_category_in_use)
    })

    it('never leaks the server message', () => {
      expect(skillAdminFailure(inUse({
        kind: 'EXPERT', name: '内容创作', usageCount: 1, packages: ['pet-care'],
      })).message).not.toContain('server copy')
    })
  })

  describe('专家页作用域（/admin/experts 复用同一套错误码）', () => {
    const experts = { noun: '专家', packageNoun: '专家包' }

    it('默认不传作用域时文案逐字不变（SKILL 页与既有断言不受影响）', () => {
      for (const code of Object.keys(SKILL_ADMIN_ERROR_MESSAGES)) {
        expect(skillAdminErrorMessage(apiError(code, 409), undefined), code)
          .toBe(SKILL_ADMIN_ERROR_MESSAGES[code])
        expect(skillAdminErrorMessage(apiError(code, 409), { noun: '技能', packageNoun: '技能包' }), code)
          .toBe(SKILL_ADMIN_ERROR_MESSAGES[code])
      }
    })

    it('把「技能 / 技能包」换成「专家 / 专家包」', () => {
      expect(skillAdminErrorMessage(apiError('skill_zip_invalid', 422), experts))
        .toContain('专家包结构校验未通过')
      expect(skillAdminErrorMessage(apiError('skill_not_found', 404), experts))
        .toBe('专家不存在或已被删除。')
      expect(skillAdminErrorMessage(apiError('skill_version_sealed', 409), experts))
        .toContain('该版本已发布并封存')
    })

    it('不误伤枚举句、三类上限列举与 YCPS 协议术语', () => {
      // `invalid_skill_kind` 在枚举 kind 的可选值，三个词都必须如实出现
      const kindMessage = skillAdminErrorMessage(apiError('invalid_skill_kind', 422), experts)
      expect(kindMessage).toContain('技能、专家或专家团')
      expect(kindMessage).toContain('专家类型不合法')

      // 三类上限列举句本就在说明三种类型各自的限制，专家页也应原样保留
      const limitMessage = skillAdminErrorMessage(apiError('skill_package_too_large', 413), experts)
      expect(limitMessage).toContain('（技能 10 MB、专家 2 MB、专家团 5 MB）')

      // 「入口技能」是 YCPS 协议术语（entry skill），与当前页面对象无关
      expect(skillAdminErrorMessage(apiError('skill_zip_invalid', 422), experts))
        .toContain('入口技能位于')
    })

    it('状态兜底文案同样按作用域本地化', () => {
      expect(skillAdminErrorMessage({ response: { status: 404, data: {} } }, experts))
        .toBe('目标专家或版本不存在，请刷新列表后重试。')
      expect(skillAdminFailure(new Error('Network Error'), experts).message)
        .toContain('无法连接到 Skill 管理服务')
    })
  })

  it('keeps the OpenAI-shaped read-only catalog map separate from the admin map', () => {
    // 管理面扁平信封与 `/console/gateway/skills`（预览标签页，OpenAI 信封）是两套解析：
    // 同名 code 在两边都要有文案，但措辞按各自的读者写；管理面独有的 code
    // 绝不能出现在只读映射里（否则只读页面会把管理面文案当成客户端文案展示）。
    const shared = new Set(Object.keys(SKILL_ERROR_MESSAGES))
    for (const code of Object.keys(SKILL_ADMIN_ERROR_MESSAGES)) {
      if (shared.has(code)) {
        expect(SKILL_ERROR_MESSAGES[code], code).toBeTruthy()
        continue
      }
      expect(SKILL_ERROR_MESSAGES[code], code).toBeUndefined()
    }
    for (const code of ['skill_cannot_delete_published', 'skill_zip_unsafe_entry', 'skill_concurrent_modification']) {
      expect(SKILL_ERROR_MESSAGES[code], code).toBeUndefined()
      expect(SKILL_ADMIN_ERROR_MESSAGES[code], code).toBeTruthy()
    }
    // 专家化改造的 6 个新码在两侧都有，但文案不同：管理面说清「怎么改」，只读面只说「不合法」
    for (const code of [
      'invalid_skill_kind',
      'invalid_expert_manifest',
      'expert_kind_mismatch',
      'invalid_skill_member',
      'skill_member_not_found',
      'skill_illustration_not_found',
      'skill_illustration_too_large',
      'skill_example_image_not_found',
      'skill_example_image_too_large',
    ]) {
      expect(SKILL_ERROR_MESSAGES[code], code).toBeTruthy()
      expect(SKILL_ADMIN_ERROR_MESSAGES[code], code).toBeTruthy()
    }
    expect(SKILL_ERROR_MESSAGES.invalid_skill_kind).not.toBe(SKILL_ADMIN_ERROR_MESSAGES.invalid_skill_kind)
  })
})
