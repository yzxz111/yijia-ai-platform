interface PlanCopy {
  name: string
  originalName: string
  description: string
  originalDescription: string
}

const standardPlanCopy: Record<string, PlanCopy> = {
  FREE: {
    name: '免费版',
    originalName: 'Free',
    description: '适合首次体验，快速了解基础调用能力。',
    originalDescription: 'One-time introductory package',
  },
  STARTER: {
    name: '入门版',
    originalName: 'Starter',
    description: '适合文字模型调用和轻量业务接入。',
    originalDescription: 'Starter text package',
  },
  ADVANCED: {
    name: '高级版',
    originalName: 'Advanced',
    description: '适合多模态模型与更高频率的业务调用。',
    originalDescription: 'Advanced multimodal package',
  },
  ENTERPRISE: {
    name: '企业版',
    originalName: 'Enterprise',
    description: '适合企业级高并发和规模化业务使用。',
    originalDescription: 'Enterprise base package',
  },
  // 视频线的中文名。数据库里五个视频套餐都存英文名（Video Starter / Video Creator / …），
  // 用户端套餐页按这里映射成中文，与管理端保持"库里存原文、展示层做本地化"的既有分工。
  // 命名按价格阶梯递增：轻量 → 基础 → 创作 → 专业，年付版跟在月付版后面。
  VIDEO_LITE: {
    name: '视频轻量版',
    originalName: 'Video Lite',
    description: '480P 入门档：每月 100 秒生成时长，按秒计费。',
    originalDescription: 'Entry video package: 100 seconds per month at 480P',
  },
  VIDEO_LITE_ANNUAL: {
    name: '视频轻量版 · 年付',
    originalName: 'Video Lite Annual',
    description: '480P 年付档：全年 1100 秒生成时长，折合每秒更便宜。',
    originalDescription: 'Annual entry video package: 1100 seconds at 480P',
  },
  VIDEO_STARTER: {
    name: '视频基础版',
    originalName: 'Video Starter',
    description: '每月 60 秒生成时长，480P / 720P / 1080P 三档可用。',
    originalDescription: 'Entry video package: 60 seconds per month at 720P',
  },
  VIDEO_CREATOR: {
    name: '视频创作版',
    originalName: 'Video Creator',
    description: '每月 190 秒生成时长，覆盖 720P 与 1080P，适合稳定产出。',
    originalDescription: 'Creator video package: 190 seconds per month, 720P and 1080P',
  },
  VIDEO_PRO: {
    name: '视频专业版',
    originalName: 'Video Pro',
    description: '每月 600 秒生成时长，全部模型档位可用，含优先队列。',
    originalDescription: 'Professional video package: 600 seconds per month, priority queue',
  },
}

function standardCopy(code: string) {
  return standardPlanCopy[code.trim().toUpperCase()]
}

export function userPlanName(code: string, fallback: string | null | undefined = '') {
  const copy = standardCopy(code)
  const value = fallback?.trim()
  if (!copy) return value || code
  return !value || value.toUpperCase() === code.trim().toUpperCase() || value === copy.originalName
    ? copy.name
    : value
}

export function userPlanDescription(code: string, fallback: string | null | undefined = '') {
  const copy = standardCopy(code)
  const value = fallback?.trim()
  if (!copy) return value || '已发布套餐版本'
  return !value || value === copy.originalDescription ? copy.description : value
}
