<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TreeNode from './TreeNode.vue'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { load, save } from '../enhance/storage.js'

const EXPANDED_KEY = 'pb.tree.expanded'
const route = useRoute()
const nodes = ref([])
const filter = ref('')
const expanded = ref(new Set(load(EXPANDED_KEY, [])))
const error = ref('')

const current = computed(() => (route.name === 'page' ? routePath(route) : ''))

onMounted(async () => {
  try {
    nodes.value = await api.tree()
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 401 ? '로그인이 필요합니다' : '트리를 불러오지 못했습니다'
  }
})

// 현재 문서의 상위 경로를 자동으로 펼친다
watch(current, (path) => {
  const parts = path.split('/')
  for (let i = 1; i < parts.length; i++) expanded.value.add(parts.slice(0, i).join('/'))
}, { immediate: true })

function toggle(path) {
  if (expanded.value.has(path)) expanded.value.delete(path)
  else expanded.value.add(path)
  save(EXPANDED_KEY, [...expanded.value])
}

// 필터: 제목 부분 일치 노드와 그 상위만 남긴다
const filtered = computed(() => {
  const term = filter.value.trim().toLowerCase()
  if (!term) return nodes.value
  const walk = (list) => list.flatMap((n) => {
    const children = walk(n.children)
    return n.title.toLowerCase().includes(term) || children.length ? [{ ...n, children }] : []
  })
  return walk(nodes.value)
})
</script>

<template>
  <div class="tree">
    <input v-model="filter" class="tree-filter" type="search" placeholder="문서 찾기" />
    <p v-if="error" class="muted">{{ error }}</p>
    <ul class="tree-list">
      <TreeNode v-for="n in filtered" :key="n.id" :node="n" :current="current" :expanded="expanded"
                :force-open="!!filter.trim()" @toggle="toggle" />
    </ul>
  </div>
</template>
