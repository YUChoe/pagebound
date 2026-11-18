<script setup>
// 분류(태그) 목록 /tags, 다중 태그 AND 목록 /tags/a+b (spec/features/F4-tags.md 2절)
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { formatDate } from '../time.js'

const route = useRoute()
const all = ref([])
const pages = ref([])
const sortByName = ref(false)

const selected = computed(() => {
  const raw = route.params.names
  const text = Array.isArray(raw) ? raw.join('/') : (raw ?? '')
  return text ? text.split('+').map((s) => s.trim()).filter(Boolean) : []
})
const sorted = computed(() => (sortByName.value
  ? [...all.value].sort((a, b) => a.name.localeCompare(b.name, 'ko'))
  : all.value))

async function load() {
  if (selected.value.length) {
    document.title = `분류: ${selected.value.join(' + ')} - Pagebound`
    pages.value = await api.tagPages(selected.value)
  } else {
    document.title = '분류 - Pagebound'
    all.value = await api.tags('', 1000)
  }
}

function link(names) {
  return '/tags/' + names.map(encodeURIComponent).join('+')
}

watch(selected, load, { immediate: true })
</script>

<template>
  <article class="doc-card">
    <template v-if="selected.length">
      <h1 class="doc-title">분류: {{ selected.join(' + ') }}</h1>
      <p class="muted">{{ pages.length }}개 문서 · <router-link to="/tags">전체 분류</router-link></p>
      <ul class="tag-pages">
        <li v-for="p in pages" :key="p.path">
          <router-link :to="'/w/' + encodePath(p.path)">{{ p.title }}</router-link>
          <span class="muted"> {{ p.path }} · {{ formatDate(p.updatedAt) }}</span>
        </li>
      </ul>
    </template>
    <template v-else>
      <h1 class="doc-title">분류</h1>
      <p class="muted">{{ all.length }}개 ·
        <button type="button" class="link-like" @click="sortByName = !sortByName">{{ sortByName ? '사용 수순' : '이름순' }}</button>
      </p>
      <div class="tag-cloud">
        <router-link v-for="t in sorted" :key="t.id" :to="link([t.name])" class="chip">{{ t.name }} <span class="muted">{{ t.count }}</span></router-link>
      </div>
    </template>
  </article>
</template>
