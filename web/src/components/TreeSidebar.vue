<script setup>
// 문서 트리 (spec/features/F3-tree-links.md 5절): 전체 트리, 필터, 드래그 앤 드롭, 컨텍스트 메뉴
import { computed, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TreeNode from './TreeNode.vue'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { load, save } from '../enhance/storage.js'
import { encodePath, parentOf } from '../markdown/wikiPaths.js'
import { moveRequest } from '../tree/dnd.js'
import { bumpTree, treeVersion } from '../tree/store.js'
import { canEdit, session } from '../session.js'

const EXPANDED_KEY = 'pb.tree.expanded'
const route = useRoute()
const router = useRouter()
const nodes = ref([])
const filter = ref('')
const expanded = ref(new Set(load(EXPANDED_KEY, [])))
const error = ref('')
const dragging = ref(null)

const current = computed(() => (route.name === 'page' || route.name === 'edit' ? routePath(route) : ''))
const editable = computed(() => session.loaded && canEdit() && !filter.value.trim())
const topPaths = computed(() => nodes.value.map((n) => n.path))

async function reload() {
  try {
    nodes.value = await api.tree()
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError && e.status === 401 ? '로그인이 필요합니다' : '트리를 불러오지 못했습니다'
  }
}
watch(treeVersion, reload, { immediate: true })

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

function fail(e) {
  window.alert(e instanceof ApiError ? (e.body?.message ?? `오류 ${e.status}`) : '작업에 실패했습니다')
}

// 이동 후 보고 있던 문서(또는 그 하위)의 경로가 바뀌면 새 경로로
function followMove(oldPath, newPath) {
  const cur = current.value
  if (cur === oldPath || cur.startsWith(oldPath + '/')) {
    router.replace(`/${route.name === 'edit' ? 'edit' : 'w'}/` + encodePath(newPath + cur.substring(oldPath.length)))
  }
}

async function move(node, body) {
  try {
    const moved = await api.movePage(node.id, body)
    bumpTree()
    followMove(node.path, moved.path)
  } catch (e) {
    fail(e)
  }
}

provide('tree', {
  editable,
  dragging,
  drop(target, zone, siblings) {
    const drag = dragging.value
    dragging.value = null
    const body = drag && moveRequest(drag.path, target.path, zone, siblings)
    if (body) {
      if (zone === 'inside') expanded.value.add(target.path)
      move(drag, body)
    }
  },
  addChild(node) {
    const name = window.prompt('하위 문서 이름', '')?.trim()
    if (name) router.push('/edit/' + encodePath(node.path + '/' + name))
  },
  rename(node) {
    const name = window.prompt('새 이름 (경로에도 반영됩니다)', node.title)?.trim()
    if (name && name !== node.title) move(node, { slug: name, title: name })
  },
  moveTo(node) {
    const parent = window.prompt('새 상위 문서 경로 (최상위는 비워 두세요)', parentOf(node.path))
    if (parent !== null) move(node, { parentPath: parent.trim() })
  },
  async trash(node) {
    if (!window.confirm(`"${node.title}" 문서를 휴지통으로 옮길까요?`)) return
    try {
      await api.trashPage(node.id)
    } catch (e) {
      if (!(e instanceof ApiError && e.body?.code === 'HAS_CHILDREN')) return fail(e)
      if (!window.confirm(`하위 문서 ${e.body.childCount}개도 함께 휴지통으로 옮깁니다. 계속할까요?`)) return
      try {
        await api.trashPage(node.id, true)
      } catch (e2) {
        return fail(e2)
      }
    }
    bumpTree()
    const cur = current.value
    if (cur === node.path || cur.startsWith(node.path + '/')) router.push('/w/home')
  },
})

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
                :force-open="!!filter.trim()" :siblings="topPaths" @toggle="toggle" />
    </ul>
  </div>
</template>
