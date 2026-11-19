import { createRouter, createWebHistory } from 'vue-router'
import { refreshSession, session, whenSessionLoaded } from './session.js'
import PageView from './components/PageView.vue'
import EditorView from './components/EditorView.vue'
import BlameView from './components/BlameView.vue'
import HistoryView from './components/HistoryView.vue'
import SourceView from './components/SourceView.vue'
import RecentView from './components/RecentView.vue'
import SearchView from './components/SearchView.vue'
import TagsView from './components/TagsView.vue'
import LoginView from './components/LoginView.vue'
import TokensView from './components/TokensView.vue'
import PasswordView from './components/PasswordView.vue'
import AdminLayout from './components/admin/AdminLayout.vue'
import AdminDashboard from './components/admin/AdminDashboard.vue'
import AdminUsers from './components/admin/AdminUsers.vue'
import AdminTrash from './components/admin/AdminTrash.vue'
import AdminTags from './components/admin/AdminTags.vue'
import AdminTokens from './components/admin/AdminTokens.vue'
import AdminAudit from './components/admin/AdminAudit.vue'
import AdminCleanup from './components/admin/AdminCleanup.vue'
import AdminSettings from './components/admin/AdminSettings.vue'
import AdminMaintenance from './components/admin/AdminMaintenance.vue'
import NotFound from './components/NotFound.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: { render: () => null } },
    { path: '/w/:path(.*)*', name: 'page', component: PageView },
    { path: '/edit/:path(.*)*', name: 'edit', component: EditorView },
    { path: '/blame/:path(.*)*', name: 'blame', component: BlameView },
    { path: '/history/:path(.*)*', name: 'history', component: HistoryView },
    { path: '/source/:path(.*)*', name: 'source', component: SourceView },
    { path: '/recent', name: 'recent', component: RecentView },
    { path: '/search', name: 'search', component: SearchView },
    { path: '/tags/:names(.*)*', name: 'tags', component: TagsView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/settings/tokens', name: 'tokens', component: TokensView },
    { path: '/settings/password', name: 'password', component: PasswordView },
    {
      path: '/admin',
      component: AdminLayout,
      children: [
        { path: '', component: AdminDashboard },
        { path: 'users', component: AdminUsers },
        { path: 'trash', component: AdminTrash },
        { path: 'tags', component: AdminTags },
        { path: 'tokens', component: AdminTokens },
        { path: 'audit', component: AdminAudit },
        { path: 'cleanup', component: AdminCleanup },
        { path: 'settings', component: AdminSettings },
        { path: 'maintenance', component: AdminMaintenance },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFound },
  ],
  scrollBehavior(to, from, saved) {
    if (saved) return saved
    if (to.hash) return { el: decodeURIComponent(to.hash), top: 64 }
    return { top: 0 }
  },
})

// '/'는 설정 site.home-path 문서로, 임시 비밀번호 사용자는 비밀번호 변경 화면으로 (F12 5·10절)
router.beforeEach(async (to) => {
  if (to.name === 'home') {
    await whenSessionLoaded()
    return { path: '/w/' + (session.homePath || 'home'), replace: true }
  }
  if (session.mustChangePassword && to.name !== 'password' && to.name !== 'login') {
    return { name: 'password' }
  }
  return true
})

// 화면 이동마다 로그인 상태 재확인: 서버에서 세션이 만료·무효화(비활성화, 역할 변경)돼도 헤더·편집 버튼이 따라간다
router.afterEach((to, from) => {
  if (session.loaded && from.matched.length) refreshSession().catch(() => {})
})

// 라우트 파라미터 → 문서 경로
export function routePath(route) {
  const p = route.params.path
  return Array.isArray(p) ? p.join('/') : (p ?? '')
}
