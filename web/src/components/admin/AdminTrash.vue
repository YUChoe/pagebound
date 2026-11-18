<script setup>
// 휴지통 (F12 6절): 복원(경로 점유 시 새 slug), 영구 삭제(제목 직접 입력 확인)
import { onMounted, ref } from 'vue'
import { adminApi, errorText, formatBytes } from '../../admin.js'
import { formatDate } from '../../time.js'

const items = ref([])
const error = ref('')
const notice = ref('')

async function load() {
  items.value = await adminApi.trash()
}

async function restore(item) {
  error.value = ''
  try {
    const r = await adminApi.restore(item.id)
    notice.value = `${r.path}로 복원했습니다.`
  } catch (e) {
    if (e.body?.code !== 'PATH_EXISTS') {
      error.value = errorText(e)
      return
    }
    const slug = prompt(`원래 경로 "${item.path}"가 사용 중입니다. 새 이름(slug)을 입력하세요.`)
    if (!slug) return
    try {
      const r = await adminApi.restore(item.id, slug)
      notice.value = `${r.path}로 복원했습니다.`
    } catch (e2) {
      error.value = errorText(e2)
    }
  }
  await load()
}

async function purge(item) {
  const typed = prompt(`영구 삭제합니다 (하위 ${item.pageCount}개, 첨부 포함). 확인을 위해 제목 "${item.title}"을(를) 입력하세요.`)
  if (typed !== item.title) return
  try {
    await adminApi.purge(item.id)
    notice.value = `${item.title} 영구 삭제됨`
  } catch (e) {
    error.value = errorText(e)
  }
  await load()
}

onMounted(load)
</script>

<template>
  <h1 class="doc-title">휴지통</h1>
  <div v-if="notice" class="banner">{{ notice }}</div>
  <div v-if="error" class="banner warn">{{ error }}</div>
  <table class="recent-table">
    <thead><tr><th>제목</th><th>원래 경로</th><th>삭제자</th><th>삭제</th><th>영구 삭제 예정</th><th>문서</th><th>첨부</th><th /></tr></thead>
    <tbody>
      <tr v-for="t in items" :key="t.id">
        <td>{{ t.title }}</td>
        <td class="muted">{{ t.path }}</td>
        <td>{{ t.deletedBy ?? '-' }}</td>
        <td class="muted">{{ formatDate(t.deletedAt) }}</td>
        <td class="muted">{{ formatDate(t.purgeAt) }}</td>
        <td>{{ t.pageCount }}</td>
        <td>{{ formatBytes(t.attachmentBytes) }}</td>
        <td class="history-actions">
          <button type="button" @click="restore(t)">복원</button>
          <button type="button" @click="purge(t)">영구 삭제</button>
        </td>
      </tr>
      <tr v-if="!items.length"><td colspan="8" class="muted">휴지통이 비었습니다.</td></tr>
    </tbody>
  </table>
</template>
