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
const JSON_HEADERS = { 'Content-Type': 'application/json' }
const json = (method, url, body) => request(method, url, JSON.stringify(body), JSON_HEADERS)

export const api = {
  me: () => request('GET', '/api/v1/me'),
  tree: (depth = 20) => request('GET', `/api/v1/tree?${q({ depth })}`),
  page: (path) => request('GET', `/api/v1/pages/by-path?${q({ path })}`),
  recent: (limit = 10) => request('GET', `/api/v1/recent?${q({ limit })}`),
  blame: (id) => request('GET', `/api/v1/pages/${id}/blame`),
  revisions: (id) => request('GET', `/api/v1/pages/${id}/revisions`),
  revision: (id, version) => request('GET', `/api/v1/pages/${id}/revisions/${version}`),
  diff: (id, from, to) => request('GET', `/api/v1/pages/${id}/diff?${q({ from, to })}`),
  restoreRevision: (id, version) => request('POST', `/api/v1/pages/${id}/revisions/${version}/restore`),
  tags: (query = '', limit = 50) => request('GET', `/api/v1/tags?${q({ q: query, limit })}`),
  tagPages: (names) => request('GET', `/api/v1/tags/pages?${q({ names: names.join(',') })}`),
  search: (params) => request('GET', `/api/v1/search?${q(params)}`),
  suggest: (query) => request('GET', `/api/v1/search/suggest?${q({ q: query })}`),
  movePage: (id, body) => json('POST', `/api/v1/pages/${id}/move`, body),
  trashPage: (id, includeChildren = false) => request('DELETE', `/api/v1/pages/${id}?${q({ includeChildren })}`),
  source: (id, section) => request('GET', `/api/v1/pages/${id}/source${section ? `?${q({ section })}` : ''}`),
  createPage: (body) => json('POST', '/api/v1/pages', body),
  createDraft: () => request('POST', '/api/v1/pages/drafts'),
  updatePage: (id, body) => json('PUT', `/api/v1/pages/${id}`, body),
  attachments: (pageId) => request('GET', `/api/v1/pages/${pageId}/attachments`),
  upload: (pageId, file, name) => {
    const form = new FormData()
    form.append('file', file, name)
    return request('POST', `/api/v1/pages/${pageId}/attachments`, form)
  },
  renameAttachment: (pageId, id, filename) =>
    json('PATCH', `/api/v1/pages/${pageId}/attachments/${id}`, { filename, rewriteContent: true }),
  deleteAttachment: (pageId, id) => request('DELETE', `/api/v1/pages/${pageId}/attachments/${id}`),
  copyAttachment: (pageId, sourcePageId, filename) =>
    json('POST', `/api/v1/pages/${pageId}/attachments/copy`, { sourcePageId, filename }),
  login: (username, password) => request('POST', '/login', q({ username, password }),
    { 'Content-Type': 'application/x-www-form-urlencoded' }),
  logout: () => request('POST', '/logout'),
  tokens: () => request('GET', '/api/v1/me/tokens'),
  linkPreviews: (urls) => json('POST', '/api/v1/link-previews', { urls }),
  refreshLinkPreview: (url) => json('POST', '/api/v1/link-previews/refresh', { url }),
  issueToken: (body) => json('POST', '/api/v1/me/tokens', body),
  revokeToken: (id) => request('DELETE', `/api/v1/me/tokens/${id}`),
}
