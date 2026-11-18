<script setup>
// 태그 관리 (F12 8절): 이름 변경, 병합(A→B), 미사용 삭제
import { onMounted, ref } from 'vue'
import { adminApi, errorText } from '../../admin.js'

const tags = ref([])
const error = ref('')

async function load() {
  tags.value = await adminApi.tags()
}

async function run(action) {
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = errorText(e)
  }
  await load()
}

function rename(t) {
  const name = prompt('새 이름', t.name)
  if (name && name !== t.name) run(() => adminApi.renameTag(t.id, name))
}

function merge(t) {
  const name = prompt(`"${t.name}"을(를) 병합할 대상 태그 이름`)
  const target = tags.value.find((x) => x.name.toLowerCase() === name?.trim().toLowerCase())
  if (!name) return
  if (!target) {
    error.value = `태그 "${name}" 없음`
    return
  }
  run(() => adminApi.mergeTag(t.id, target.id))
}

function remove(t) {
  if (confirm(`미사용 태그 "${t.name}"을(를) 삭제할까요?`)) run(() => adminApi.deleteTag(t.id))
}

onMounted(load)
</script>

<template>
  <h1 class="doc-title">태그</h1>
  <div v-if="error" class="banner warn">{{ error }}</div>
  <table class="recent-table">
    <thead><tr><th>이름</th><th>사용 수</th><th /></tr></thead>
    <tbody>
      <tr v-for="t in tags" :key="t.id">
        <td><router-link :to="'/tags/' + encodeURIComponent(t.name)">{{ t.name }}</router-link></td>
        <td>{{ t.count }}</td>
        <td class="history-actions">
          <button type="button" @click="rename(t)">이름 변경</button>
          <button type="button" @click="merge(t)">병합</button>
          <button v-if="!t.count" type="button" @click="remove(t)">삭제</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
