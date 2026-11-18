import { createRouter, createWebHistory } from 'vue-router'
import PageView from './components/PageView.vue'
import RecentView from './components/RecentView.vue'
import LoginView from './components/LoginView.vue'
import NotFound from './components/NotFound.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/w/home' },
    { path: '/w/:path(.*)*', name: 'page', component: PageView },
    { path: '/recent', name: 'recent', component: RecentView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound },
  ],
  scrollBehavior(to, from, saved) {
    if (saved) return saved
    if (to.hash) return { el: decodeURIComponent(to.hash), top: 64 }
    return { top: 0 }
  },
})

// 라우트 파라미터 → 문서 경로
export function routePath(route) {
  const p = route.params.path
  return Array.isArray(p) ? p.join('/') : (p ?? '')
}
