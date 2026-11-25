import { platformHttp } from './http'

/** 「我的邀请码」概览；activeCode 为 null 表示还没生成过。 */
export type ReferralOverview = {
  activeCode: string | null
  successCount: number
}

export type ReferralInvitee = {
  userId: string
  registeredAt: string
}

export type ReferralInviteePage = {
  items: ReferralInvitee[]
  nextCursor: string | null
}

/**
 * 邀请归因链路的控制台入口。
 *
 * 后端（gitee 合并进来的 `ReferralController`）一直有这 4 个接口，但此前前端一处都没调用，
 * 于是「邀请码」功能对用户完全不可见：看不到自己的码、也没法把码发出去。
 */
export async function fetchReferralOverview() {
  return (await platformHttp.get<ReferralOverview>('/console/platform/referrals/me')).data
}

/** 幂等：已有在用的码就原样返回，没有才生成。 */
export async function ensureReferralCode() {
  return (await platformHttp.post<ReferralOverview>('/console/platform/referrals/codes')).data
}

/** 换一个码（旧码立即失效，已产生的归因不受影响）。 */
export async function rotateReferralCode() {
  return (await platformHttp.post<ReferralOverview>('/console/platform/referrals/codes/rotate')).data
}

export async function fetchReferralInvitees(limit = 20, cursor?: string) {
  return (
    await platformHttp.get<ReferralInviteePage>('/console/platform/referrals/me/invitees', {
      params: { limit, ...(cursor ? { cursor } : {}) },
    })
  ).data
}

/** 邀请链接：登录页会读取 `?invite=` 并把它一路带到「设置密码」页的邀请码输入框。 */
export function referralInviteLink(code: string) {
  const base = `${window.location.origin}/console/login`
  return `${base}?invite=${encodeURIComponent(code)}`
}
