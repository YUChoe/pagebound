<script setup>
// 본인 비밀번호 변경 /settings/password (spec/features/F7-common.md 4절). 임시 비밀번호 로그인 시 강제 이동
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'
import { refreshSession, session } from '../session.js'

const router = useRouter()
const form = ref({ current: '', next: '', confirm: '' })
const error = ref('')
const done = ref(false)

async function submit() {
  error.value = ''
  if (form.value.next !== form.value.confirm) {
    error.value = '새 비밀번호가 일치하지 않습니다.'
    return
  }
  try {
    await api.changePassword(form.value.current, form.value.next)
    done.value = true
    await refreshSession()
    router.push('/')
  } catch (e) {
    error.value = e.body?.code === 'WRONG_PASSWORD' ? '현재 비밀번호가 틀렸습니다.' : (e.body?.message ?? e.message)
  }
}
</script>

<template>
  <article class="doc-card">
    <h1 class="doc-title">비밀번호 변경</h1>
    <p v-if="session.mustChangePassword" class="banner warn">임시 비밀번호로 로그인했습니다. 새 비밀번호를 설정하세요.</p>
    <form class="settings-form" @submit.prevent="submit">
      <label><span>현재 비밀번호</span><input v-model="form.current" type="password" autocomplete="current-password" required></label>
      <label><span>새 비밀번호 (8자 이상)</span><input v-model="form.next" type="password" minlength="8" autocomplete="new-password" required></label>
      <label><span>새 비밀번호 확인</span><input v-model="form.confirm" type="password" minlength="8" autocomplete="new-password" required></label>
      <button type="submit" class="primary">변경</button>
      <span v-if="error" class="badge warn">{{ error }}</span>
      <span v-if="done" class="badge">변경됨</span>
    </form>
  </article>
</template>
