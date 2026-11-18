<script setup>
import { computed, inject, ref } from 'vue'
import { encodePath } from '../markdown/wikiPaths.js'
import { dropZone, moveRequest } from '../tree/dnd.js'

const props = defineProps({
  node: { type: Object, required: true },
  current: { type: String, default: '' },
  expanded: { type: Set, required: true },
  forceOpen: { type: Boolean, default: false },
  siblings: { type: Array, default: () => [] }, // 같은 부모의 자식 경로 (정렬 순)
})
const emit = defineEmits(['toggle'])
const tree = inject('tree')
const open = computed(() => props.forceOpen || props.expanded.has(props.node.path))
const zone = ref(null)
const menuOpen = ref(false)
const childPaths = computed(() => props.node.children.map((c) => c.path))

function onDragStart(e) {
  tree.dragging.value = props.node
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', props.node.path)
}

function onDragOver(e) {
  const drag = tree.dragging.value
  if (!drag) return
  const z = dropZone(e.offsetY, e.currentTarget.clientHeight)
  zone.value = moveRequest(drag.path, props.node.path, z, props.siblings) ? z : null
  if (zone.value) e.preventDefault()
}

function onDrop() {
  const z = zone.value
  zone.value = null
  if (z) tree.drop(props.node, z, props.siblings)
}

function act(name) {
  menuOpen.value = false
  tree[name](props.node)
}
</script>

<template>
  <li class="tree-node">
    <div class="tree-row" :class="{ active: node.path === current, ['drop-' + zone]: zone }"
         :draggable="tree.editable.value" @dragstart="onDragStart" @dragend="tree.dragging.value = null"
         @dragover="onDragOver" @dragleave="zone = null" @drop.prevent="onDrop">
      <button v-if="node.childCount" type="button" class="tree-caret" @click="emit('toggle', node.path)">
        {{ open ? '▾' : '▸' }}
      </button>
      <span v-else class="tree-caret" />
      <router-link :to="'/w/' + encodePath(node.path)" :title="node.path">{{ node.title }}</router-link>
      <button v-if="tree.editable.value" type="button" class="tree-more" title="문서 메뉴" @click="menuOpen = !menuOpen">⋯</button>
      <div v-if="menuOpen" class="tree-menu" @mouseleave="menuOpen = false">
        <button type="button" @click="act('addChild')">하위 문서 추가</button>
        <button type="button" @click="act('rename')">이름 변경</button>
        <button type="button" @click="act('moveTo')">이동</button>
        <button type="button" @click="act('trash')">휴지통으로</button>
      </div>
    </div>
    <ul v-if="open && node.children.length" class="tree-list">
      <TreeNode v-for="c in node.children" :key="c.id" :node="c" :current="current" :expanded="expanded"
                :force-open="forceOpen" :siblings="childPaths" @toggle="(p) => emit('toggle', p)" />
    </ul>
  </li>
</template>
