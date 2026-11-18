<script setup>
// 설정 (F12 10절): DB settings 저장, 즉시 반영. 비우면 기본값. GitLab은 연결 상태만 표시
import { onMounted, ref } from 'vue'
import { adminApi, errorText } from '../../admin.js'
import { refreshSession } from '../../session.js'

const LABELS = {
  'site.name': '사이트 이름', 'site.base-url': '외부 주소 (영구 링크·MCP)', 'site.home-path': '첫 화면 문서 경로',
  'security.anonymous-read': '익명 읽기 (true/false)', 'upload.max-file-size': '첨부 최대 크기 (≤52MB)',
  'trash.retention-days': '휴지통 보관 일수', 'draft.retention-hours': '초안 보관 시간',
  'mcp.rate-limit-per-minute': '토큰당 분당 호출 한도',
}
const items = ref([])
const gitlab = ref([])
const values = ref({})
const notice = ref('')
const error = ref('')

function apply(r) {
  items.value = r.items
  gitlab.value = r.gitlab
  values.value = Object.fromEntries(r.items.map((i) => [i.key, i.overridden ? i.value : '']))
}

async function save() {
  error.value = ''
  const changes = {}
  items.value.forEach((i) => {
    const v = values.value[i.key].trim()
    if (v !== (i.overridden ? i.value : '')) changes[i.key] = v
  })
  if (!Object.keys(changes).length) return
  try {
    apply(await adminApi.updateSettings(changes))
    await refreshSession()
    notice.value = `${Object.keys(changes).length}개 저장, 즉시 반영됨`
  } catch (e) {
    error.value = errorText(e)
  }
}

onMounted(async () => apply(await adminApi.settings()))
</script>

<template>
  <h1 class="doc-title">설정</h1>
  <div v-if="notice" class="banner">{{ notice }}</div>
  <div v-if="error" class="banner warn">{{ error }}</div>
  <form class="settings-form" @submit.prevent="save">
    <label v-for="i in items" :key="i.key">
      <span>{{ LABELS[i.key] ?? i.key }} <code class="muted">{{ i.key }}</code></span>
      <input v-model="values[i.key]" :placeholder="'기본값: ' + (i.defaultValue || '(비어 있음)')">
    </label>
    <button type="submit" class="primary">저장</button>
  </form>
  <h2>GitLab 연결 (링크 미리보기)</h2>
  <p class="muted">토큰은 환경변수로만 설정합니다 (GITLAB_A_URL/TOKEN, GITLAB_B_URL/TOKEN).</p>
  <ul>
    <li v-for="g in gitlab" :key="g.name">{{ g.name }} {{ g.baseUrl }} ·
      <span v-if="!g.hasToken" class="badge warn">토큰 없음</span>
      <span v-else class="badge" :class="{ warn: g.status !== 200 }">HTTP {{ g.status }} {{ g.version ?? '' }}</span></li>
    <li v-if="!gitlab.length" class="muted">설정된 GitLab 없음</li>
  </ul>
</template>
