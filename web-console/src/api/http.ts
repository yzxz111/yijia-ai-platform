import axios, {
  AxiosHeaders,
  type AxiosError,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'

import { useAuthStore, type AuthenticatedUser } from '../stores/auth'
import { getDeviceId } from '../utils/deviceId'

interface RefreshSession {
  accessToken: string
  expiresIn: number
  user: AuthenticatedUser
}

interface AuthRecoveryOptions {
  refresh?: () => Promise<RefreshSession>
  onExpired?: (reason: AuthExpiryReason) => void
  includeDeviceId?: boolean
}

export type AuthExpiryReason = 'expired' | 'session_replaced'

interface AuthScope {
  tenantId: string
  userId: string
  sessionEpoch: number
}

interface RetriableRequest extends InternalAxiosRequestConfig {
  _authScope?: AuthScope
  _authRetried?: boolean
}

const refreshesInFlight = new Map<string, Promise<RefreshSession>>()

export function readCookie(name: string): string | null {
  const prefix = `${encodeURIComponent(name)}=`
  const value = document.cookie.split('; ').find((entry) => entry.startsWith(prefix))
  return value ? decodeURIComponent(value.slice(prefix.length)) : null
}

async function refreshSession(): Promise<RefreshSession> {
  const csrfToken = readCookie('yzh_csrf')
  if (!csrfToken) throw new Error('csrf_cookie_missing')
  const response = await axios.post<RefreshSession>(
    '/console/platform/auth/refresh',
    null,
    {
      withCredentials: true,
      headers: {
        Accept: 'application/json',
        'X-CSRF-Token': csrfToken,
        'X-Device-Id': getDeviceId(),
      },
    },
  )
  return response.data
}

function emitAuthExpired(reason: AuthExpiryReason) {
  window.dispatchEvent(new CustomEvent<AuthExpiryReason>('auth:expired', { detail: reason }))
}

function errorCode(error: unknown): string | undefined {
  return (error as AxiosError<{ code?: string }>)?.response?.data?.code
}

function recoveryKey(scope: AuthScope): string {
  return `${scope.sessionEpoch}:${scope.tenantId}:${scope.userId}`
}

function refreshOnce(scope: AuthScope, recover: () => Promise<RefreshSession>): Promise<RefreshSession> {
  const key = recoveryKey(scope)
  const existing = refreshesInFlight.get(key)
  if (existing) return existing

  const recovery = Promise.resolve().then(recover)
  const tracked = recovery.finally(() => {
    if (refreshesInFlight.get(key) === tracked) refreshesInFlight.delete(key)
  })
  refreshesInFlight.set(key, tracked)
  return tracked
}

function captureAuthScope(): AuthScope | null {
  const auth = useAuthStore()
  if (!auth.user) return null
  return {
    tenantId: auth.user.tenantId,
    userId: auth.user.id,
    sessionEpoch: auth.sessionEpoch,
  }
}

function isCurrentAuthScope(scope: AuthScope | undefined): scope is AuthScope {
  if (!scope) return false
  const current = captureAuthScope()
  return current?.tenantId === scope.tenantId
    && current.userId === scope.userId
    && current.sessionEpoch === scope.sessionEpoch
}

function isSameIdentity(scope: AuthScope, user: AuthenticatedUser): boolean {
  return scope.tenantId === user.tenantId && scope.userId === user.id
}

export async function restoreSession(): Promise<boolean> {
  const auth = useAuthStore()
  if (auth.isAuthenticated) return true
  const restoreScope: AuthScope = {
    tenantId: '',
    userId: '',
    sessionEpoch: auth.sessionEpoch,
  }
  try {
    const session = await refreshOnce(restoreScope, refreshSession)
    if (auth.sessionEpoch !== restoreScope.sessionEpoch || auth.isAuthenticated) return auth.isAuthenticated
    auth.setSession(session.accessToken, session.user)
    return true
  } catch (error) {
    if (auth.sessionEpoch !== restoreScope.sessionEpoch || auth.isAuthenticated) return auth.isAuthenticated
    auth.clearSession()
    if (errorCode(error) === 'session_replaced') emitAuthExpired('session_replaced')
    return false
  }
}

export function createApiClient(options: AuthRecoveryOptions = {}): AxiosInstance {
  const client = axios.create({
    withCredentials: true,
    headers: { Accept: 'application/json' },
  })

  client.interceptors.request.use((config) => {
    const auth = useAuthStore()
    if (options.includeDeviceId) {
      config.headers.set('X-Device-Id', getDeviceId())
    }
    const csrfToken = readCookie('yzh_csrf')
    if (csrfToken && !config.url?.includes('/console/platform/auth/')) {
      config.headers.set('X-CSRF-Token', csrfToken)
    }
    if (auth.accessToken) {
      config.headers.Authorization = `Bearer ${auth.accessToken}`
    }
    if (auth.user) {
      const request = config as RetriableRequest
      request._authScope = {
        tenantId: auth.user.tenantId,
        userId: auth.user.id,
        sessionEpoch: auth.sessionEpoch,
      }
    }
    return config
  })

  const recover = options.refresh || refreshSession
  const expire = options.onExpired || emitAuthExpired

  client.interceptors.response.use(undefined, async (error: AxiosError) => {
    const request = error.config as RetriableRequest | undefined
    const auth = useAuthStore()
    const maintainsSession = request?.url === '/console/platform/auth/terminal-presence'
      || request?.url === '/console/platform/auth/login-handoffs/pending'
    const isAuthEndpoint = request?.url?.includes('/console/platform/auth/') && !maintainsSession
    if (
      error.response?.status !== 401
      || !request
      || request._authRetried
      || isAuthEndpoint
      || !auth.isAuthenticated
      || !isCurrentAuthScope(request._authScope)
    ) {
      throw error
    }

    request._authRetried = true
    const requestScope = request._authScope
    let session: RefreshSession
    try {
      session = await refreshOnce(requestScope, recover)
    } catch (refreshError) {
      if (!isCurrentAuthScope(requestScope)) throw error
      auth.clearSession()
      expire(errorCode(refreshError) === 'session_replaced' ? 'session_replaced' : 'expired')
      throw error
    }
    if (!isCurrentAuthScope(requestScope)) throw error
    auth.setSession(session.accessToken, session.user)
    if (!isSameIdentity(requestScope, session.user)) throw error
    request.headers = AxiosHeaders.from(request.headers)
    request.headers.set('Authorization', `Bearer ${session.accessToken}`)
    return client.request(request)
  })

  return client
}

export const platformHttp = createApiClient({ includeDeviceId: true })
export const gatewayHttp = createApiClient({ includeDeviceId: true })
