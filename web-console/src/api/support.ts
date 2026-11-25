import { platformHttp } from './http'

export type SupportTicketStatus = 'OPEN' | 'PROCESSING' | 'COMPLETED' | 'CLOSED'

export interface SupportComment {
  id: string
  authorUserId: string
  authorRole: string
  body: string
  createdAt: string
}

export interface SupportAttachment {
  id: string
  commentId: string | null
  filename: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface SupportTicket {
  id: string
  ticketNo: string
  tenantId: string
  userId: string
  assigneeUserId: string | null
  title: string
  body: string
  status: SupportTicketStatus
  resolution: string | null
  completionBusinessNo: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  comments: SupportComment[] | null
}

export interface SupportTicketPage {
  items: SupportTicket[]
  total: number
  page: number
  size: number
}

export function listSupportTickets(page = 0, size = 20) {
  return platformHttp.get<SupportTicketPage>('/console/platform/support-tickets', { params: { page, size } })
}

export function getSupportTicket(id: string) {
  return platformHttp.get<SupportTicket>(`/console/platform/support-tickets/${id}`)
}

export function createSupportTicket(payload: { title: string; body: string }, attachments: File[] = []) {
  if (attachments.length) {
    const form = new FormData()
    form.append('title', payload.title)
    form.append('body', payload.body)
    attachments.forEach((file) => form.append('attachments', file))
    return platformHttp.post<SupportTicket>('/console/platform/support-tickets', form)
  }
  return platformHttp.post<SupportTicket>('/console/platform/support-tickets', payload)
}

export function commentSupportTicket(id: string, body: string, attachments: File[] = []) {
  if (attachments.length) {
    const form = new FormData()
    form.append('body', body)
    attachments.forEach((file) => form.append('attachments', file))
    return platformHttp.post<SupportTicket>(`/console/platform/support-tickets/${id}/comments`, form)
  }
  return platformHttp.post<SupportTicket>(`/console/platform/support-tickets/${id}/comments`, { body })
}

export function listSupportTicketAttachments(id: string) {
  return platformHttp.get<SupportAttachment[]>(`/console/platform/support-tickets/${id}/attachments`)
}

export function getSupportTicketAttachment(id: string, attachmentId: string) {
  return platformHttp.get<Blob>(`/console/platform/support-tickets/${id}/attachments/${attachmentId}`, {
    responseType: 'blob',
  })
}

export function closeSupportTicket(id: string) {
  return platformHttp.post<SupportTicket>(`/console/platform/support-tickets/${id}/close`)
}
