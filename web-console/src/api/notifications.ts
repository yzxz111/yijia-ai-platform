import { platformHttp } from './http'

/**
 * 必须与后端 `NotificationTypeRegistry.Category` 及数据库的
 * `ck_notification_event_category` / `ck_notification_preference_category` 保持一致。
 * `SUBSCRIPTION` 是自动续费的订阅生命周期通知；它不是 `BILLING`，因为其中的
 * 扣款前预告不允许用户关闭（合规要求），后端只对合规分类开放强制短信。
 */
export type NotificationCategory =
  | 'SECURITY' | 'BILLING' | 'USAGE' | 'OPERATIONS' | 'SUPPORT' | 'SUBSCRIPTION'
export type NotificationChannel = 'IN_APP' | 'SMS'

export interface NotificationItem {
  id: string
  category: NotificationCategory
  eventType: string
  title: string
  summary: string
  aggregateType: string
  aggregateId: string
  readAt: string | null
  archivedAt: string | null
  createdAt: string
}

export interface NotificationPage {
  items: NotificationItem[]
  total: number
  page: number
  size: number
}

export interface NotificationFilters {
  category?: NotificationCategory
  read?: boolean
  page?: number
  size?: number
}

export interface NotificationPreference {
  category: NotificationCategory
  channel: NotificationChannel
  enabled: boolean
  quietStart: string
  quietEnd: string
  timezone: 'Asia/Shanghai'
}

export function listNotifications(filters: NotificationFilters = {}) {
  return platformHttp.get<NotificationPage>('/console/platform/notifications', { params: filters })
}

export function getUnreadCount() {
  return platformHttp.get<{ count: number }>('/console/platform/notifications/unread-count')
}

export function markNotificationRead(id: string) {
  return platformHttp.post(`/console/platform/notifications/${id}/read`)
}

export function markAllNotificationsRead() {
  return platformHttp.post('/console/platform/notifications/read-all')
}

export function archiveNotification(id: string) {
  return platformHttp.post(`/console/platform/notifications/${id}/archive`)
}

export function getNotificationPreferences() {
  return platformHttp.get<NotificationPreference[]>('/console/platform/notification-preferences')
}

export function updateNotificationPreferences(preferences: NotificationPreference[]) {
  return platformHttp.put<NotificationPreference[]>('/console/platform/notification-preferences', { preferences })
}

export function unsubscribeSms() {
  return platformHttp.post('/console/platform/notification-preferences/unsubscribe-sms')
}
