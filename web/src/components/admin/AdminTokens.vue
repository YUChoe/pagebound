<script setup>
// API 토큰·에이전트 (F12 9절): 전체 토큰, 강제 폐기, MCP 호출 로그·토큰별 일간 호출 수
import { onMounted, ref, watch } from 'vue'
import { adminApi, errorText } from '../../admin.js'
import { formatDate } from '../../time.js'

const tokens = ref([])
const calls = ref(null)
const filter = ref({ token: '', tool: '', result: '', page: 1 })
const error = ref('')

async function load() {
  tokens.value = await adminApi.tokens()
}

async function loadCalls() {
  calls.value = await adminApi.mcpCalls(filter.value)
}

async function revoke(t) {
  if (!confirm(`${t.userName}의 토큰 "${t.name}"을(를) 강제 폐기할까요?`)) return
  try {
    await adminApi.revokeToken(t.id)
  } catch (e) {
    error.value = errorText(e)
  }
  await load()
}

onMounted(() => { load(); loadCalls() })
watch(filter, loadCalls, { deep: true })
</script>

<template>
  <h1 class="doc-title">API 토큰·에이전트</h1>
  <div v-if="error" class="banner warn">{{ error }}</div>
  <table class="recent-table">
    <thead><tr><th>사용자</th><th>이름</th><th>scope</th><th>발급</th><th>만료</th><th>마지막 사용</th><th /></tr></thead>
    <tbody>
      <tr v-for="t in tokens" :key="t.id" :class="{ muted: t.revokedAt }">
        <td>{{ t.userName }}</td>
        <td>{{ t.name }}</td>
        <td><span class="badge">{{ t.scope }}</span></td>
        <td class="muted">{{ formatDate(t.createdAt) }}</td>
        <td class="muted">{{ t.expiresAt ? formatDate(t.expiresAt) : '무기한' }}</td>
        <td class="muted">{{ t.lastUsedAt ? formatDate(t.lastUsedAt) : '-' }}</td>
        <td><button v-if="!t.revokedAt" type="button" @click="revoke(t)">강제 폐기</button><span v-else>폐기됨</span></td>
      </tr>
    </tbody>
  </table>

  <h2>MCP 호출 로그 <small class="muted">(90일 보관)</small></h2>
  <p class="token-form">
    <select v-model="filter.token"><option value="">모든 토큰</option>
      <option v-for="t in tokens" :key="t.id" :value="t.id">{{ t.userName }} · {{ t.name }}</option></select>
    <input v-model.lazy="filter.tool" placeholder="도구 (예: update_page)">
    <select v-model="filter.result"><option value="">모든 결과</option><option value="OK">OK</option><option value="ERROR">오류만</option></select>
  </p>
  <template v-if="calls">
    <p class="muted">최근 14일 일간 호출:
      <span v-for="d in calls.daily" :key="d.day + d.tokenId" class="chip">{{ d.day }} {{ d.tokenName ?? '-' }} {{ d.calls }}</span></p>
    <table class="recent-table">
      <thead><tr><th>시각</th><th>토큰</th><th>도구</th><th>대상</th><th>결과</th></tr></thead>
      <tbody>
        <tr v-for="c in calls.items" :key="c.id">
          <td class="muted">{{ formatDate(c.createdAt) }}</td>
          <td>{{ c.user }} · {{ c.tokenName }}</td>
          <td>{{ c.tool }}</td>
          <td>{{ c.target }}</td>
          <td><span class="badge" :class="{ warn: c.result !== 'OK' }">{{ c.result }}</span></td>
        </tr>
      </tbody>
    </table>
    <p>{{ calls.total }}건 ·
      <button type="button" :disabled="filter.page <= 1" @click="filter.page--">이전</button>
      {{ filter.page }}
      <button type="button" :disabled="filter.page * 50 >= calls.total" @click="filter.page++">다음</button></p>
  </template>
</template>
