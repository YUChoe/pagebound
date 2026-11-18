import { createRouter, createWebHistory } from 'vue-router'
import PageView from './components/PageView.vue'
import EditorView from './components/EditorView.vue'
import BlameView from './components/BlameView.vue'
import RecentView from './components/RecentView.vue'
import SearchView from './components/SearchView.vue'
import TagsView from './components/TagsView.vue'
import LoginView from './components/LoginView.vue'
import TokensView from './components/TokensView.vue'
import NotFound from './components/NotFound.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/w/home' },
    { path: '/w/:path(.*)*', name: 'page', component: PageView },
    { path: '/edit/:path(.*)*', name: 'edit', component: EditorView },
    { path: '/blame/:path(.*)*', name: 'blame', component: BlameView },
    { path: '/recent', name: 'recent', component: RecentView },
    { path: '/search', name: 'search', component: SearchView },
    { path: '/tags/:names(.*)*', name: 'tags', component: TagsView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/settings/tokens', name: 'tokens', component: TokensView },
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
