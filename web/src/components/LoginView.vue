<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api.js'
import { refreshSession } from '../session.js'

const route = useRoute()
const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await refreshSession() // XSRF-TOKEN 쿠키 확보
    await api.login(email.value, password.value)
    await refreshSession()
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') ? route.query.next : '/'
    router.replace(next)
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 401 ? '이메일 또는 비밀번호가 올바르지 않습니다' : '로그인에 실패했습니다'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <article class="doc-card login-card">
    <h1 class="doc-title">로그인</h1>
    <form @submit.prevent="submit">
      <label>이메일 <input v-model="email" type="email" autocomplete="username" required /></label>
      <label>비밀번호 <input v-model="password" type="password" autocomplete="current-password" required /></label>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="busy">로그인</button>
    </form>
  </article>
</template>
