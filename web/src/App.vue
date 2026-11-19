<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TreeSidebar from './components/TreeSidebar.vue'
import RecentSidebar from './components/RecentSidebar.vue'
import SearchBox from './components/SearchBox.vue'
import { api } from './api.js'
import { refreshSession, session } from './session.js'
import { THEME_ICON, THEME_LABEL, cycleTheme, theme } from './theme.js'

const route = useRoute()
const router = useRouter()
const treeOpen = ref(false)

onMounted(() => {
  refreshSession()
  // 오래 열어 둔 탭으로 돌아오면 로그인 상태 재확인 (세션 만료 후 헤더에 이전 사용자가 남지 않게)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refreshSession().catch(() => {})
  })
})
watch(() => route.fullPath, () => { treeOpen.value = false })

async function logout() {
  await api.logout()
  await refreshSession()
  router.go(0)
}
</script>

<template>
  <header class="topbar">
    <button class="tree-toggle" type="button" title="문서 트리" @click="treeOpen = !treeOpen">☰</button>
    <router-link to="/" class="logo">{{ session.siteName || 'Pagebound' }}</router-link>
    <nav class="topnav">
      <router-link to="/recent">최근 변경</router-link>
      <router-link to="/tags">분류</router-link>
      <router-link v-if="session.role === 'ADMIN'" to="/admin">관리</router-link>
    </nav>
    <SearchBox />
    <span class="spacer" />
    <button type="button" class="link-btn" :title="'테마: ' + THEME_LABEL[theme.mode]" @click="cycleTheme">{{ THEME_ICON[theme.mode] }}</button>
    <span v-if="session.authenticated" class="user">
      {{ session.name }}
      <router-link to="/settings/tokens">API 토큰</router-link>
      <router-link to="/settings/password">비밀번호</router-link>
      <button type="button" class="link-btn" @click="logout">로그아웃</button>
    </span>
    <router-link v-else-if="session.loaded" :to="{ name: 'login', query: { next: route.fullPath } }">로그인</router-link>
  </header>
  <div class="layout" :class="{ 'tree-open': treeOpen, editing: route.name === 'edit' }">
    <aside class="tree-pane">
      <TreeSidebar />
    </aside>
    <main class="main-pane">
      <router-view />
    </main>
    <aside class="recent-pane">
      <RecentSidebar />
    </aside>
  </div>
</template>
