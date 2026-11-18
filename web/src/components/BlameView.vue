<script setup>
// 행별 blame 화면 /blame/{path} (spec/features/F13-blame.md 3절)
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { SOURCE_LABELS, ageLevels, blameRows } from '../blame.js'
import { setWrap } from '../enhance/codeBlocks.js'
import { formatDate, relativeTime } from '../time.js'

const route = useRoute()
const page = ref(null)
const blame = ref(null)
const status = ref('loading')

const rows = computed(() => (blame.value ? blameRows(blame.value.content, blame.value.hunks) : []))
const levels = computed(() => (blame.value ? ageLevels(blame.value.revisions) : {}))

function info(version) {
  return blame.value.revisions[version] ?? {}
}

function tooltip(version) {
  const r = info(version)
  return [`v${version}`, r.author ?? '알 수 없음', formatDate(r.createdAt), (SOURCE_LABELS[r.source] ?? r.source) + (r.tokenName ? ` (${r.tokenName})` : ''),
    r.summary ? `요약: ${r.summary}` : ''].filter(Boolean).join(' · ')
}

// 코드블록 줄바꿈 설정과 공유
function toggleWrap() {
  setWrap(!document.documentElement.classList.contains('code-wrap'))
}

async function load() {
  status.value = 'loading'
  try {
    page.value = await api.page(routePath(route))
    blame.value = await api.blame(page.value.id)
    document.title = `행별 이력: ${page.value.title} - Pagebound`
    status.value = 'ok'
  } catch (e) {
    status.value = e instanceof ApiError && e.status === 404 ? 'missing' : 'error'
  }
}

watch(() => routePath(route), load, { immediate: true })
</script>

<template>
  <div v-if="status === 'loading'" class="muted pad">계산 중…</div>
  <article v-else-if="status !== 'ok'" class="doc-card">
    <p>{{ status === 'missing' ? '문서를 찾을 수 없습니다.' : '행별 이력을 불러오지 못했습니다.' }}</p>
  </article>
  <article v-else class="doc-card blame-page">
    <div class="doc-title-row">
      <h1 class="doc-title">행별 이력: {{ page.title }}</h1>
      <div class="doc-actions">
        <button type="button" @click="toggleWrap">줄바꿈</button>
        <router-link class="btn" :to="'/w/' + encodePath(page.path)">문서로</router-link>
      </div>
    </div>
    <p class="muted">v{{ blame.version }} · {{ rows.length }}줄 · 리비전 {{ Object.keys(blame.revisions).length }}개가 현재 내용에 남아 있음</p>
    <div class="blame-table" role="table">
      <div v-for="row in rows" :key="row.no" class="blame-row" :class="{ first: row.first }" role="row"
           :style="{ '--age': levels[row.version] }" :title="tooltip(row.version)">
        <span class="blame-info" role="cell">
          <template v-if="row.first">
            <span class="blame-ver">v{{ row.version }}</span>
            <span class="blame-author">{{ info(row.version).author ?? '알 수 없음' }}</span>
            <span class="muted">{{ relativeTime(info(row.version).createdAt) }}</span>
            <span v-if="info(row.version).source !== 'web'" class="badge" :class="'src-' + info(row.version).source">
              {{ SOURCE_LABELS[info(row.version).source] ?? info(row.version).source }}{{
                info(row.version).tokenName ? ' · ' + info(row.version).tokenName : '' }}</span>
          </template>
        </span>
        <span class="blame-no" role="cell">{{ row.no }}</span>
        <code class="blame-text" role="cell">{{ row.text }}</code>
      </div>
    </div>
  </article>
</template>
