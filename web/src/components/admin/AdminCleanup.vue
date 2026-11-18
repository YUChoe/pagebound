<script setup>
// 정리 도구 (F12 7절): 미참조 첨부(선택 삭제·다른 문서로 이동), 레드 링크(문서 만들기), 빈 문서, 초안
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../api.js'
import { adminApi, errorText, formatBytes } from '../../admin.js'
import { encodePath } from '../../markdown/wikiPaths.js'
import { formatDate } from '../../time.js'

const KINDS = { 'unreferenced-attachments': '미참조 첨부', 'red-links': '레드 링크', 'empty-pages': '빈 문서', drafts: '초안' }
const route = useRoute()
const router = useRouter()
const kind = ref(route.query.kind ?? 'unreferenced-attachments')
const items = ref([])
const selected = ref(new Set())
const notice = ref('')
const error = ref('')

async function load() {
  selected.value = new Set()
  items.value = await adminApi.cleanup(kind.value)
}

function toggle(key) {
  const s = new Set(selected.value)
  if (s.has(key)) s.delete(key)
  else s.add(key)
  selected.value = s
}

const keyOf = (a) => `${a.pageId}:${a.attachmentId}`

async function deleteSelected() {
  const list = items.value.filter((a) => selected.value.has(keyOf(a)))
  if (!list.length || !confirm(`첨부 ${list.length}개를 삭제할까요?`)) return
  await each(list, (a) => api.deleteAttachment(a.pageId, a.attachmentId))
}

async function moveSelected() {
  const list = items.value.filter((a) => selected.value.has(keyOf(a)))
  const target = list.length && prompt(`첨부 ${list.length}개를 옮길 문서 경로`)
  if (!target) return
  try {
    const page = await api.page(target)
    await each(list, async (a) => {
      await api.copyAttachment(page.id, a.pageId, a.filename)
      await api.deleteAttachment(a.pageId, a.attachmentId)
    })
  } catch (e) {
    error.value = errorText(e)
  }
}

async function each(list, action) {
  error.value = ''
  let done = 0
  for (const a of list) {
    try {
      await action(a)
      done++
    } catch (e) {
      error.value = `${a.filename}: ${errorText(e)}`
    }
  }
  notice.value = `${done}개 처리`
  await load()
}

async function deleteDraft(d) {
  if (!confirm('초안을 삭제할까요? 첨부도 함께 삭제됩니다.')) return
  await adminApi.deleteDraft(d.id)
  await load()
}

onMounted(load)
watch(kind, (k) => { router.replace({ query: { kind: k } }); load() })
</script>

<template>
  <h1 class="doc-title">정리 도구</h1>
  <p class="admin-tabs">
    <button v-for="(label, k) in KINDS" :key="k" type="button" :class="{ primary: kind === k }" @click="kind = k">{{ label }}</button>
  </p>
  <div v-if="notice" class="banner">{{ notice }}</div>
  <div v-if="error" class="banner warn">{{ error }}</div>

  <template v-if="kind === 'unreferenced-attachments'">
    <p><button type="button" :disabled="!selected.size" @click="deleteSelected">선택 삭제</button>
      <button type="button" :disabled="!selected.size" @click="moveSelected">선택 이동</button>
      <span class="muted"> {{ items.length }}개 · {{ formatBytes(items.reduce((s, a) => s + a.size, 0)) }}</span></p>
    <table class="recent-table">
      <thead><tr><th /><th>문서</th><th>파일</th><th>크기</th></tr></thead>
      <tbody>
        <tr v-for="a in items" :key="keyOf(a)">
          <td><input type="checkbox" :checked="selected.has(keyOf(a))" @change="toggle(keyOf(a))"></td>
          <td><router-link :to="'/w/' + encodePath(a.path)">{{ a.path }}</router-link></td>
          <td><a :href="`/p/${a.pageId}/files/${encodeURIComponent(a.filename)}`" target="_blank">{{ a.filename }}</a></td>
          <td>{{ formatBytes(a.size) }}</td>
        </tr>
      </tbody>
    </table>
  </template>
  <table v-else-if="kind === 'red-links'" class="recent-table">
    <thead><tr><th>대상 경로</th><th>참조 수</th><th>참조 문서</th><th /></tr></thead>
    <tbody>
      <tr v-for="r in items" :key="r.target">
        <td>{{ r.target }}</td>
        <td>{{ r.references }}</td>
        <td class="muted">{{ r.sources.join(', ') }}</td>
        <td><router-link class="btn" :to="'/edit/' + encodePath(r.target)">문서 만들기</router-link></td>
      </tr>
    </tbody>
  </table>
  <table v-else class="recent-table">
    <thead><tr><th>경로</th><th>제목</th><th>시각</th><th /></tr></thead>
    <tbody>
      <tr v-for="p in items" :key="p.id">
        <td><router-link v-if="kind === 'empty-pages'" :to="'/w/' + encodePath(p.path)">{{ p.path }}</router-link><span v-else>{{ p.path }}</span></td>
        <td>{{ p.title }}</td>
        <td class="muted">{{ formatDate(p.at) }}</td>
        <td><button v-if="kind === 'drafts'" type="button" @click="deleteDraft(p)">삭제</button></td>
      </tr>
      <tr v-if="!items.length"><td colspan="4" class="muted">없음</td></tr>
    </tbody>
  </table>
</template>
