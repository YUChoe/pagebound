<script setup>
// 검색 결과 화면 (spec/features/F11-search.md 5절)
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { highlight, highlightTerms, queryTerms } from '../search/highlight.js'
import { headingId } from '../markdown/plugins.js'
import { canEdit } from '../session.js'
import { formatDate } from '../time.js'

const route = useRoute()
const router = useRouter()
const result = ref(null)
const loading = ref(false)
const error = ref('')

const q = computed(() => (typeof route.query.q === 'string' ? route.query.q : ''))
const tags = computed(() => (typeof route.query.tags === 'string' && route.query.tags ? route.query.tags.split(',') : []))
const path = computed(() => (typeof route.query.path === 'string' ? route.query.path : ''))
const sort = computed(() => (route.query.sort === 'updated' ? 'updated' : 'relevance'))
const page = computed(() => Number(route.query.page) || 1)
const terms = computed(() => queryTerms(q.value))
const pages = computed(() => (result.value ? Math.ceil(result.value.total / result.value.size) : 0))

async function load() {
  document.title = `검색: ${q.value} - Pagebound`
  loading.value = true
  error.value = ''
  try {
    result.value = await api.search({ q: q.value, tags: tags.value.join(','), path: path.value, sort: sort.value,
      page: page.value, size: 20 })
  } catch {
    error.value = '검색하지 못했습니다'
  } finally {
    loading.value = false
  }
}

function update(changes) {
  const query = { ...route.query, ...changes, page: changes.page ?? undefined }
  Object.keys(query).forEach((k) => (query[k] === '' || query[k] === undefined) && delete query[k])
  router.push({ name: 'search', query })
}

function toggleTag(name) {
  const set = new Set(tags.value)
  if (set.has(name)) set.delete(name)
  else set.add(name)
  update({ tags: [...set].join(',') })
}

watch(() => route.fullPath, () => { if (route.name === 'search') load() }, { immediate: true })

// 결과 → 문서 (?hl=검색어로 본문 강조, 섹션 바로가기는 해당 헤딩으로)
function pageLink(item, section) {
  const query = terms.value.length ? { hl: terms.value.join(' ') } : {}
  return { path: '/w/' + encodePath(item.path), query, hash: section ? '#' + headingId(section.title) : '' }
}
</script>

<template>
  <article class="doc-card search-page">
    <h1 class="doc-title">검색</h1>
    <div class="search-meta">
      <span v-if="result">{{ result.total }}건</span>
      <span v-if="path" class="chip">경로: {{ path }} <button type="button" @click="update({ path: '' })">×</button></span>
      <span v-for="t in tags" :key="t" class="chip">분류: {{ t }} <button type="button" @click="toggleTag(t)">×</button></span>
      <span class="spacer" />
      <label>정렬
        <select :value="sort" @change="update({ sort: $event.target.value })">
          <option value="relevance">관련도</option>
          <option value="updated">최근 수정</option>
        </select>
      </label>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="result" class="search-layout">
      <aside class="facets">
        <details open>
          <summary>분류</summary>
          <label v-for="f in result.tagFacets" :key="f.name">
            <input type="checkbox" :checked="tags.includes(f.name)" @change="toggleTag(f.name)" /> {{ f.name }}
            <span class="muted">{{ f.count }}</span>
          </label>
        </details>
        <details open>
          <summary>경로</summary>
          <label v-for="f in result.pathFacets" :key="f.name">
            <input type="radio" name="path" :checked="path === f.name" @change="update({ path: f.name })" /> {{ f.name }}
            <span class="muted">{{ f.count }}</span>
          </label>
        </details>
      </aside>
      <section class="results">
        <p v-if="loading" class="muted">검색 중…</p>
        <div v-else-if="!result.items.length" class="empty">
          <p>검색 결과가 없습니다. 철자를 확인하거나 필터를 줄여 보세요.</p>
          <router-link v-if="canEdit() && q.trim()" class="btn" :to="'/edit/' + encodePath(q.trim())">
            ‘{{ q.trim() }}’ 문서 만들기
          </router-link>
        </div>
        <ol v-else class="result-list">
          <li v-for="item in result.items" :key="item.path" class="result">
            <div class="result-head">
              <router-link class="result-title" :to="pageLink(item)"><span v-html="highlightTerms(item.title, terms)" /></router-link>
              <span v-if="item.files?.length" class="badge" :title="item.files.join(', ')">첨부 {{ item.files.length }}</span>
              <span class="muted">{{ formatDate(item.updatedAt) }}<template v-if="item.updatedBy"> · {{ item.updatedBy }}</template></span>
            </div>
            <div class="muted result-path">
              <template v-for="(c, i) in item.breadcrumbs" :key="c.path"><span v-if="i"> › </span>{{ c.title }}</template>
              <template v-if="item.tags.length"> · 분류: {{ item.tags.join(', ') }}</template>
            </div>
            <p v-for="(s, i) in item.snippets" :key="i" class="snippet" :class="{ code: s.inCode }" v-html="highlight(s.text)" />
            <router-link v-if="item.section" class="muted section-link" :to="pageLink(item, item.section)">↳ {{ item.section.number }} {{ item.section.title }}</router-link>
          </li>
        </ol>
        <nav v-if="pages > 1" class="pager">
          <button type="button" :disabled="page <= 1" @click="update({ page: page - 1 })">이전</button>
          <span>{{ page }} / {{ pages }}</span>
          <button type="button" :disabled="page >= pages" @click="update({ page: page + 1 })">다음</button>
        </nav>
      </section>
    </div>
  </article>
</template>
