<template>
  <div class="workflow-canvas" :class="{ fullscreen }">
    <div class="canvas-toolbar">
      <div class="canvas-toolbar-group">
        <button class="canvas-btn" :class="{ active: direction === 'LR' }" @click="direction = 'LR'">
          {{ t('workflows.canvas.layoutLR') }}
        </button>
        <button class="canvas-btn" :class="{ active: direction === 'TB' }" @click="direction = 'TB'">
          {{ t('workflows.canvas.layoutTB') }}
        </button>
      </div>
      <div class="canvas-toolbar-group">
        <span class="canvas-stat">{{ t('workflows.canvas.stats', { nodes: graph.nodes.length, edges: graph.edges.length }) }}</span>
        <span v-if="graph.parseError" class="canvas-stat err">{{ t('workflows.canvas.parseError', { msg: graph.parseError }) }}</span>
        <button
          class="canvas-btn icon-btn"
          :title="fullscreen ? t('workflows.canvas.fullscreenExit') : t('workflows.canvas.fullscreenEnter')"
          @click="toggleFullscreen"
        >
          <svg v-if="!fullscreen" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 9V3h6"/><path d="M21 9V3h-6"/><path d="M3 15v6h6"/><path d="M21 15v6h-6"/>
          </svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 3v6H3"/><path d="M15 3v6h6"/><path d="M9 21v-6H3"/><path d="M15 21v-6h6"/>
          </svg>
        </button>
      </div>
    </div>

    <div v-if="!graph.nodes.length && !graph.parseError" class="canvas-empty">
      {{ t('workflows.canvas.empty') }}
    </div>

    <VueFlow
      v-else-if="!graph.parseError"
      :id="canvasId"
      class="canvas-flow"
      :nodes="graph.nodes"
      :edges="graph.edges"
      :node-types="nodeTypes"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="true"
      :pan-on-drag="true"
      :zoom-on-scroll="true"
      :min-zoom="0.4"
      :max-zoom="1.8"
      :default-edge-options="defaultEdgeOptions"
      fit-view-on-init
      @node-click="handleNodeClick"
      @pane-click="handlePaneClick"
    >
      <Background :pattern-color="dotColor" :gap="20" />
      <Controls show-zoom show-fit-view show-interactive />
      <MiniMap
        :node-color="miniNodeColor"
        :node-stroke-color="miniNodeStrokeColor"
        :node-class-name="(n: any) => 'mini-' + (n?.data?.modeType ?? 'sequential')"
        pannable
        zoomable
      />
    </VueFlow>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { VueFlow, type Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import StepNode from './StepNode.vue'
import { useWorkflowGraph, type StepNodeData } from '@/composables/useWorkflowGraph'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

interface Props {
  /** Workflow draft JSON — the source of truth. Canvas is read-only over it. */
  modelValue: string
  /** Stable id so multiple canvases on the page don't collide in vue-flow's state map. */
  canvasId?: string
}

const props = withDefaults(defineProps<Props>(), { canvasId: 'workflow-canvas' })

const emit = defineEmits<{
  (e: 'select-step', payload: StepNodeData | null): void
}>()

const { t } = useI18n()

const direction = ref<'LR' | 'TB'>('LR')
const jsonRef = computed(() => props.modelValue)
const graph = useWorkflowGraph(jsonRef, direction)

// Register the custom node component once. markRaw prevents Vue from
// turning the component definition into a deep proxy, which vue-flow
// recommends for performance.
const nodeTypes = {
  step: markRaw(StepNode),
}

// Match the rest of the app's accent.
const defaultEdgeOptions = {
  type: 'smoothstep' as const,
  animated: false,
  style: { stroke: 'var(--mc-edge, #999999)', strokeWidth: 1.5 },
}

const dotColor = 'var(--mc-canvas-dot, rgba(0, 0, 0, 0.16))'
const miniNodeColor = (node: Node<StepNodeData>) => {
  switch (node.data?.modeType) {
    case 'fan_out': return '#b388ff'
    case 'collect': return '#7e57c2'
    case 'conditional': return '#ff8f3f'
    case 'await_approval': return '#ffb84d'
    case 'dispatch_channel': return '#2ecc71'
    case 'write_memory': return '#1abc9c'
    default: return '#4084ff'
  }
}
const miniNodeStrokeColor = () => 'transparent'

function handleNodeClick(payload: { node: Node<StepNodeData> }) {
  emit('select-step', payload?.node?.data ?? null)
}
function handlePaneClick() {
  emit('select-step', null)
}

// Reset direction toggle highlight when external code resets the model.
watch(jsonRef, () => {
  /* nothing to do — the graph computed re-runs automatically */
})

// Fullscreen toggle. We use a CSS-driven overlay rather than the
// Fullscreen API so the rest of the page (sidebar utilities, app
// header) stays styled consistently and the operator can still drag a
// dialog over the canvas if needed.
const fullscreen = ref(false)
function toggleFullscreen() {
  fullscreen.value = !fullscreen.value
}
function handleEsc(e: KeyboardEvent) {
  if (e.key === 'Escape' && fullscreen.value) fullscreen.value = false
}
watch(fullscreen, (on) => {
  if (on) {
    document.addEventListener('keydown', handleEsc)
    // Lock page scroll behind the overlay.
    document.body.style.overflow = 'hidden'
  } else {
    document.removeEventListener('keydown', handleEsc)
    document.body.style.overflow = ''
  }
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleEsc)
  document.body.style.overflow = ''
})

</script>

<style scoped>
.workflow-canvas {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 320px;
  flex: 1;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 8px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.02));
  color: var(--mc-text-primary, inherit);
  overflow: hidden;
}
.workflow-canvas.fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  border-radius: 0;
  border: none;
  min-height: 100vh;
  background: var(--mc-bg, #ffffff);
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  padding: 0;
  height: 26px;
}
.canvas-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  font-size: 12px;
  background: var(--mc-bg-elevated, transparent);
}
.canvas-toolbar-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.canvas-btn {
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
}
.canvas-btn.active {
  background: var(--mc-primary, #4084ff);
  border-color: var(--mc-primary, #4084ff);
  color: #ffffff;
}
.canvas-stat {
  opacity: 0.75;
}
.canvas-stat.err {
  color: #c0392b;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.canvas-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  opacity: 0.7;
  padding: 24px;
}
.canvas-flow {
  flex: 1;
  width: 100%;
  height: 100%;
  min-height: 280px;
}
</style>
