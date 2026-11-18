<script setup>
// 사용자 관리 (F12 5절)
import { computed, onMounted, ref } from 'vue'
import { adminApi, errorText } from '../../admin.js'
import { formatDate } from '../../time.js'

const users = ref([])
const filter = ref({ text: '', role: '', state: '' })
const form = ref({ email: '', name: '', role: 'EDITOR', password: '' })
const notice = ref('')
const error = ref('')

const shown = computed(() => users.value.filter((u) => {
  const t = filter.value.text.trim().toLowerCase()
  return (!t || u.name.toLowerCase().includes(t) || u.email.toLowerCase().includes(t))
    && (!filter.value.role || u.role === filter.value.role)
    && (!filter.value.state || (filter.value.state === 'active') === u.active)
}))

async function load() {
  users.value = await adminApi.users()
}

async function run(action) {
  error.value = ''
  notice.value = ''
  try {
    await action()
    await load()
  } catch (e) {
    error.value = errorText(e)
  }
}

function create() {
  run(async () => {
    const r = await adminApi.createUser(form.value)
    notice.value = r.temporaryPassword
      ? `${r.email} 생성됨. 임시 비밀번호(1회 표시): ${r.temporaryPassword}`
      : `${r.email} 생성됨. 다음 로그인 시 비밀번호 변경이 요구됩니다.`
    form.value = { email: '', name: '', role: 'EDITOR', password: '' }
  })
}

function setRole(u, role) {
  run(() => adminApi.updateUser(u.id, { role }))
}

function toggleActive(u) {
  if (u.active && !confirm(`${u.name}을(를) 비활성화할까요? 세션과 API 토큰이 즉시 무효가 됩니다.`)) return
  run(() => adminApi.updateUser(u.id, { active: !u.active }))
}

function reset(u) {
  if (!confirm(`${u.name}의 비밀번호를 초기화할까요?`)) return
  run(async () => {
    const r = await adminApi.resetPassword(u.id)
    notice.value = `${u.email} 임시 비밀번호(1회 표시): ${r.temporaryPassword}`
  })
}

onMounted(load)
</script>

<template>
  <h1 class="doc-title">사용자</h1>
  <div v-if="notice" class="banner"><code>{{ notice }}</code></div>
  <div v-if="error" class="banner warn">{{ error }}</div>
  <form class="token-form" @submit.prevent="create">
    <input v-model="form.email" type="email" placeholder="이메일" required>
    <input v-model="form.name" placeholder="이름" required>
    <select v-model="form.role"><option>EDITOR</option><option>VIEWER</option><option>ADMIN</option></select>
    <input v-model="form.password" type="password" placeholder="초기 비밀번호 (비우면 자동 생성)" minlength="8" autocomplete="new-password">
    <button type="submit" class="primary">생성</button>
  </form>
  <p class="token-form">
    <input v-model="filter.text" placeholder="이름·이메일 검색">
    <select v-model="filter.role"><option value="">모든 역할</option><option>ADMIN</option><option>EDITOR</option><option>VIEWER</option></select>
    <select v-model="filter.state"><option value="">모든 상태</option><option value="active">활성</option><option value="inactive">비활성</option></select>
  </p>
  <table class="recent-table">
    <thead><tr><th>이름</th><th>이메일</th><th>역할</th><th>상태</th><th>마지막 로그인</th><th>토큰</th><th>편집</th><th /></tr></thead>
    <tbody>
      <tr v-for="u in shown" :key="u.id" :class="{ muted: !u.active }">
        <td>{{ u.name }}<span v-if="u.mustChangePassword" class="badge warn">비번 변경 필요</span></td>
        <td>{{ u.email }}</td>
        <td><select :value="u.role" @change="setRole(u, $event.target.value)"><option>ADMIN</option><option>EDITOR</option><option>VIEWER</option></select></td>
        <td>{{ u.active ? '활성' : '비활성' }}</td>
        <td class="muted">{{ u.lastLoginAt ? formatDate(u.lastLoginAt) : '-' }}</td>
        <td>{{ u.tokenCount }}</td>
        <td>{{ u.editCount }}</td>
        <td class="history-actions">
          <button type="button" @click="toggleActive(u)">{{ u.active ? '비활성화' : '활성화' }}</button>
          <button type="button" @click="reset(u)">비밀번호 초기화</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
