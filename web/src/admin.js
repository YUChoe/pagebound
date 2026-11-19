// 관리자 API (spec/features/F12-admin.md 14절)
import { api } from './api.js'

const q = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '' && v != null)).toString()

export const adminApi = {
  dashboard: () => api.raw('GET', '/api/v1/admin/dashboard'),
  users: () => api.raw('GET', '/api/v1/admin/users'),
  createUser: (body) => api.rawJson('POST', '/api/v1/admin/users', body),
  updateUser: (id, body) => api.rawJson('PATCH', `/api/v1/admin/users/${id}`, body),
  resetPassword: (id) => api.raw('POST', `/api/v1/admin/users/${id}/reset-password`),
  userActivity: (id) => api.raw('GET', `/api/v1/admin/users/${id}/activity`),
  trash: () => api.raw('GET', '/api/v1/admin/trash'),
  restore: (id, slug) => api.rawJson('POST', `/api/v1/admin/trash/${id}/restore`, slug ? { slug } : {}),
  purge: (id) => api.raw('DELETE', `/api/v1/admin/trash/${id}`),
  tags: () => api.raw('GET', '/api/v1/tags?limit=1000&includeUnused=true'),
  renameTag: (id, name) => api.rawJson('PATCH', `/api/v1/tags/${id}`, { name }),
  mergeTag: (id, targetId) => api.rawJson('POST', `/api/v1/tags/${id}/merge`, { targetId }),
  deleteTag: (id) => api.raw('DELETE', `/api/v1/tags/${id}`),
  tokens: () => api.raw('GET', '/api/v1/admin/tokens'),
  revokeToken: (id) => api.raw('DELETE', `/api/v1/admin/tokens/${id}`),
  mcpCalls: (params) => api.raw('GET', `/api/v1/admin/mcp-calls?${q(params)}`),
  audit: (params) => api.raw('GET', `/api/v1/admin/audit?${q(params)}`),
  settings: () => api.raw('GET', '/api/v1/admin/settings'),
  updateSettings: (changes) => api.rawJson('PUT', '/api/v1/admin/settings', changes),
  cleanup: (kind) => api.raw('GET', `/api/v1/admin/cleanup/${kind}`),
  deleteDraft: (id) => api.raw('DELETE', `/api/v1/admin/drafts/${id}`),
  maintenance: (task) => api.raw('POST', `/api/v1/admin/maintenance/${task}`),
  migrationReport: () => api.raw('GET', '/api/v1/admin/maintenance/migration-report'),
}

export function formatBytes(n) {
  if (n == null) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(i ? 1 : 0)} ${units[i]}`
}

export function errorText(e) {
  return e?.body?.message ? `${e.body.code}: ${e.body.message}` : e?.message ?? String(e)
}
