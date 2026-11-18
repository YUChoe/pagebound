import { reactive } from 'vue'
import { api } from './api.js'

// 현재 사용자 (GET /api/v1/me 응답). /me 호출이 XSRF-TOKEN 쿠키도 발급한다.
export const session = reactive({ loaded: false, authenticated: false, anonymousRead: true, name: '', role: '' })

export async function refreshSession() {
  const me = await api.me()
  Object.assign(session, { name: '', role: '' }, me, { loaded: true })
}

export function canEdit() {
  return session.authenticated && (session.role === 'EDITOR' || session.role === 'ADMIN')
}
