import { reactive, watch } from 'vue'
import { api } from './api.js'

// 현재 사용자 (GET /api/v1/me 응답). /me 호출이 XSRF-TOKEN 쿠키도 발급한다.
export const session = reactive({
  loaded: false, authenticated: false, anonymousRead: true, name: '', role: '', siteName: 'Pagebound', homePath: 'home',
  mustChangePassword: false,
})

export async function refreshSession() {
  const me = await api.me()
  Object.assign(session, { name: '', role: '', mustChangePassword: false }, me, { loaded: true })
}

// /me 응답 이후 실행 (새로고침 직후 렌더가 세션 확인보다 빠를 수 있음)
export function whenSessionLoaded() {
  if (session.loaded) return Promise.resolve()
  return new Promise((resolve) => {
    const stop = watch(() => session.loaded, (loaded) => {
      if (!loaded) return
      stop()
      resolve()
    })
  })
}

export function canEdit() {
  return session.authenticated && (session.role === 'EDITOR' || session.role === 'ADMIN')
}
