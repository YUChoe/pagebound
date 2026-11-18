// 서버 API 호출. 쓰기 요청에는 쿠키 XSRF-TOKEN 값을 X-XSRF-TOKEN 헤더로 보낸다.

function xsrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : ''
}

export class ApiError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

async function request(method, url, body, headers = {}) {
  const res = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers: method === 'GET' ? headers : { 'X-XSRF-TOKEN': xsrfToken(), ...headers },
    body,
  })
  const type = res.headers.get('content-type') ?? ''
  const data = type.includes('json') ? await res.json() : null
  if (!res.ok) throw new ApiError(res.status, data)
  return data
}

const q = (params) => new URLSearchParams(params).toString()

export const api = {
  me: () => request('GET', '/api/v1/me'),
  tree: (depth = 20) => request('GET', `/api/v1/tree?${q({ depth })}`),
  page: (path) => request('GET', `/api/v1/pages/by-path?${q({ path })}`),
  recent: (limit = 10) => request('GET', `/api/v1/recent?${q({ limit })}`),
  login: (username, password) => request('POST', '/login', q({ username, password }),
    { 'Content-Type': 'application/x-www-form-urlencoded' }),
  logout: () => request('POST', '/logout'),
}
