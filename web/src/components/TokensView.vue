<script setup>
// API 토큰 관리 /settings/tokens (spec/features/F6-mcp-api.md 2절). 발급 직후에만 원문을 보여 준다.
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api.js'
import { session } from '../session.js'
import { formatDate } from '../time.js'

const tokens = ref([])
const form = ref({ name: '', scope: 'read', expiresInDays: 90 })
const issued = ref(null)
const error = ref('')
const mcpCommand = computed(() => issued.value
  ? `claude mcp add --transport http pagebound ${location.origin}/mcp --header "Authorization: Bearer ${issued.value.token}"`
  : '')

async function load() {
  tokens.value = await api.tokens()
}

async function issue() {
  error.value = ''
  try {
    issued.value = await api.issueToken({ ...form.value, expiresInDays: form.value.expiresInDays || null })
    form.value.name = ''
    await load()
  } catch (e) {
    error.value = e.body?.message ?? e.message
  }
}

async function revoke(t) {
  if (!confirm(`토큰 "${t.name}"을 폐기할까요? 이 토큰을 쓰는 클라이언트는 즉시 401을 받습니다.`)) return
  await api.revokeToken(t.id)
  await load()
}

function copy(text) {
  navigator.clipboard?.writeText(text)
}

function state(t) {
  if (t.revokedAt) return '폐기됨'
  if (t.expiresAt && new Date(t.expiresAt) < new Date()) return '만료됨'
  return '사용 가능'
}

onMounted(() => { document.title = 'API 토큰 - Pagebound' })
// 새로고침 직후에는 세션 확인(/me)이 끝난 뒤 목록을 불러온다
watch(() => session.authenticated, (yes) => { if (yes) load() }, { immediate: true })
</script>

<template>
  <article class="doc-card">
    <h1 class="doc-title">API 토큰</h1>
    <p v-if="!session.authenticated" class="muted">로그인이 필요합니다.</p>
    <template v-else>
      <p class="muted">MCP(<code>/mcp</code>)와 REST API(<code>/api/v1</code>)에 <code>Authorization: Bearer</code>로 사용합니다.
        실제 권한은 scope와 내 역할 중 낮은 쪽이며, 토큰으로는 관리 기능을 쓸 수 없습니다.</p>

      <form class="token-form" @submit.prevent="issue">
        <input v-model="form.name" placeholder="이름 (예: claude-code 노트북)" maxlength="100" required>
        <select v-model="form.scope">
          <option value="read">read (읽기)</option>
          <option value="write">write (읽기 + 쓰기)</option>
        </select>
        <select v-model.number="form.expiresInDays">
          <option :value="30">30일</option>
          <option :value="90">90일</option>
          <option :value="0">무기한</option>
        </select>
        <button type="submit" class="primary">발급</button>
        <span v-if="error" class="badge warn">{{ error }}</span>
      </form>

      <div v-if="issued" class="banner token-issued">
        <p>토큰 "{{ issued.name }}"이 발급되었습니다. 이 화면을 벗어나면 다시 볼 수 없습니다.</p>
        <p><code>{{ issued.token }}</code> <button type="button" @click="copy(issued.token)">복사</button></p>
        <p class="muted">Claude Code 등록:</p>
        <p><code>{{ mcpCommand }}</code> <button type="button" @click="copy(mcpCommand)">복사</button></p>
      </div>

      <table class="recent-table">
        <thead><tr><th>이름</th><th>scope</th><th>발급</th><th>만료</th><th>마지막 사용</th><th>상태</th><th /></tr></thead>
        <tbody>
          <tr v-for="t in tokens" :key="t.id">
            <td>{{ t.name }}</td>
            <td><span class="badge">{{ t.scope }}</span></td>
            <td class="muted">{{ formatDate(t.createdAt) }}</td>
            <td class="muted">{{ t.expiresAt ? formatDate(t.expiresAt) : '무기한' }}</td>
            <td class="muted">{{ t.lastUsedAt ? formatDate(t.lastUsedAt) : '-' }}</td>
            <td>{{ state(t) }}</td>
            <td><button v-if="!t.revokedAt" type="button" @click="revoke(t)">폐기</button></td>
          </tr>
          <tr v-if="!tokens.length"><td colspan="7" class="muted">발급한 토큰이 없습니다.</td></tr>
        </tbody>
      </table>
    </template>
  </article>
</template>
