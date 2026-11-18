<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TreeSidebar from './components/TreeSidebar.vue'
import RecentSidebar from './components/RecentSidebar.vue'
import SearchBox from './components/SearchBox.vue'
import { api } from './api.js'
import { refreshSession, session } from './session.js'

const route = useRoute()
const router = useRouter()
const treeOpen = ref(false)

onMounted(refreshSession)
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
    <router-link to="/w/home" class="logo">Pagebound</router-link>
    <nav class="topnav">
      <router-link to="/recent">최근 변경</router-link>
      <router-link to="/tags">분류</router-link>
    </nav>
    <SearchBox />
    <span class="spacer" />
    <span v-if="session.authenticated" class="user">
      {{ session.name }}
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
