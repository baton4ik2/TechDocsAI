const TOKEN_KEY = 'techdocs_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(options.headers as Record<string, string>) }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(path, { ...options, headers })
  if (response.status === 401) {
    setToken(null)
    window.location.href = '/login'
    throw new ApiError(401, 'Требуется авторизация')
  }
  if (!response.ok) {
    let message = 'Ошибка запроса'
    try {
      const body = await response.json()
      message = body.message || message
    } catch { /* нет тела */ }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

/**
 * Открывает документ в новой вкладке. Прямая ссылка на /download не работает —
 * браузер не передаёт JWT из localStorage, поэтому качаем файл авторизованным
 * запросом и открываем как blob. `page` — переход к странице PDF (#page=N).
 */
export async function openDocument(documentId: number, page?: number | null) {
  const response = await fetch(`/api/documents/${documentId}/download`, {
    headers: { Authorization: `Bearer ${getToken()}` },
  })
  if (!response.ok) throw new ApiError(response.status, 'Не удалось открыть документ')
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  window.open(page ? `${url}#page=${page}` : url, '_blank')
  // отложенная очистка: вкладка уже получила содержимое
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  postForm: <T>(path: string, form: FormData) =>
    request<T>(path, { method: 'POST', body: form }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: (path: string) => request<void>(path, { method: 'DELETE' }),
}
