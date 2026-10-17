export class ApiError extends Error {
  constructor(public status: number, message?: string) {
    super(status === 401 ? '登录已失效，请重新登录' : message || (status === 403 ? '没有访问权限，请刷新后重试' : '请求失败，请稍后重试'))
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.method && init.method !== 'GET') {
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
    if (!response.ok) throw new ApiError(response.status)
    const csrf: { headerName: string; token: string } = await response.json()
    headers.set(csrf.headerName, csrf.token)
  }
  const response = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new ApiError(response.status, typeof problem.message === 'string' ? problem.message : undefined)
  }
  return response.status === 204 ? undefined as T : response.json()
}

export interface Account {
  id: number
  username: string
  displayName: string
  departmentId: number
  departmentName: string
  roles: string[]
}

export interface EventSummary {
  id: number
  personName: string | null
  eventTime: string
  eventCategory: string
  disputeType: string
  eventDescription: string
  eventSource: string
  departmentName: string
  isCompleted: boolean
}

export interface EventPage { items: EventSummary[]; total: number }
