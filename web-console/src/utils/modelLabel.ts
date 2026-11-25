const userModelLabels: Record<string, string> = {
  'text-basic': '文本模型',
  'vision-basic': '视觉模型',
  'file-basic': '文件模型',
  'multimodal-basic': '多模态模型',
  // 视频别名按秒而非按 token 计量。该标签说明
  // 别名属于哪一档，套餐卡片才能展示这些秒数能买到什么。
  'video-budget': '视频模型 · 480P',
  'video-basic': '视频模型 · 720P',
  'video-standard': '视频模型 · 1080P',
  'video-pro': '视频模型 · 精品',
  'video-max': '视频模型 · 4K',
}

export function userModelLabel(code: string) {
  return userModelLabels[code] || code
}

/** 网关按秒而非按 token 计价的别名返回 true。 */
export function isVideoModelCode(code: string) {
  return code.startsWith('video-')
}
