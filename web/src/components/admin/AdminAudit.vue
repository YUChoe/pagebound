<script setup>
// 감사 로그 (F12 12절): 기간·행위자·작업 종류 필터, 50건 단위
import { onMounted, ref, watch } from 'vue'
import { adminApi } from '../../admin.js'
import { formatDate } from '../../time.js'

const ACTIONS = ['USER_CREATE', 'USER_ROLE', 'USER_ACTIVATE', 'USER_DEACTIVATE', 'USER_PASSWORD_RESET', 'PAGE_TRASH',
  'PAGE_RESTORE', 'PAGE_PURGE', 'TAG_RENAME', 'TAG_MERGE', 'TAG_DELETE', 'TOKEN_ISSUE', 'TOKEN_REVOKE',
  'SETTING_UPDATE', 'MAINTENANCE', 'DRAFT_DELETE', 'LOGIN_FAILED']
const users = ref([])
const filter = ref({ from: '', to: '', actor: '', action: '', page: 1 })
const result = ref(null)

async function load() {
  const f = filter.value
  result.value = await adminApi.audit({
    ...f,
    from: f.from ? new Date(f.from).toISOString() : '',
    to: f.to ? new Date(new Date(f.to).getTime() + 86400000).toISOString() : '',
  })
}

onMounted(async () => {
  users.value = await adminApi.users()
  load()
})
watch(filter, load, { deep: true })
</script>

<template>
  <h1 class="doc-title">감사 로그</h1>
  <p class="token-form">
    <input v-model="filter.from" type="date"> ~ <input v-model="filter.to" type="date">
    <select v-model="filter.actor"><option value="">모든 행위자</option>
      <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option></select>
    <select v-model="filter.action"><option value="">모든 작업</option><option v-for="a in ACTIONS" :key="a">{{ a }}</option></select>
  </p>
  <template v-if="result">
    <table class="recent-table">
      <thead><tr><th>시각</th><th>행위자</th><th>작업</th><th>대상</th><th>상세</th></tr></thead>
      <tbody>
        <tr v-for="a in result.items" :key="a.id">
          <td class="muted">{{ formatDate(a.createdAt) }}</td>
          <td>{{ a.actor ?? '시스템' }}</td>
          <td>{{ a.action }}</td>
          <td>{{ a.targetType }} {{ a.targetId }}</td>
          <td class="muted"><code v-if="a.detail">{{ a.detail }}</code></td>
        </tr>
      </tbody>
    </table>
    <p>{{ result.total }}건 ·
      <button type="button" :disabled="filter.page <= 1" @click="filter.page--">이전</button>
      {{ filter.page }}
      <button type="button" :disabled="filter.page * result.size >= result.total" @click="filter.page++">다음</button></p>
  </template>
</template>
