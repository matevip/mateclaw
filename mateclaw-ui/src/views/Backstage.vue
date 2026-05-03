<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner backstage-page">
        <!-- Header: one sentence, no jargon -->
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('backstage.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('backstage.title') }}</h1>
            <p class="mc-page-desc">{{ headlineMessage }}</p>
          </div>
          <div class="header-actions">
            <button
              class="chip-btn"
              :class="{ 'is-paused': !autoRefresh }"
              :title="autoRefresh ? t('backstage.actions.pauseRefresh') : t('backstage.actions.resumeRefresh')"
              @click="toggleAutoRefresh"
            >
              <span class="chip-pulse" v-if="autoRefresh"></span>
              <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3"/>
              </svg>
              <span>{{ autoRefresh ? t('backstage.actions.live') : t('backstage.actions.paused') }}</span>
            </button>
            <button
              v-if="(snapshot?.summary?.stuck ?? 0) > 0"
              class="chip-btn chip-btn-warm"
              @click="confirmSweep"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M3 6h18"/>
                <path d="M19 6l-1.2 14a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 6"/>
                <path d="M10 11v6M14 11v6"/>
              </svg>
              {{ t('backstage.actions.tidyUp') }}
            </button>
          </div>
        </div>

        <!-- Loading -->
        <div v-if="isInitialLoading" class="cards-grid">
          <div v-for="i in 3" :key="i" class="agent-card mc-surface-card agent-card-skeleton">
            <el-skeleton :rows="2" animated />
          </div>
        </div>

        <!-- Empty: nothing to see -->
        <div v-else-if="snapshot && snapshot.runs.length === 0" class="empty-still">
          <div class="empty-orb"></div>
          <div class="empty-line">{{ t('backstage.empty.allQuiet') }}</div>
          <div class="empty-hint">{{ t('backstage.empty.hint') }}</div>
        </div>

        <!-- Cards -->
        <div v-else class="cards-grid">
          <article
            v-for="run in snapshot?.runs ?? []"
            :key="run.conversationId"
            class="agent-card mc-surface-card"
            :class="cardClass(run)"
            @click="openDetail(run)"
          >
            <!-- Top: avatar + name + breathing dot -->
            <div class="agent-card-top">
              <div class="agent-avatar" :style="avatarBgStyle(run)">
                <SkillIcon
                  v-if="run.agentIcon"
                  :value="run.agentIcon"
                  :size="34"
                  fallback="🤖"
                />
                <span v-else class="agent-avatar-letter">{{ avatarLetter(run) }}</span>
              </div>
              <div class="agent-id">
                <div class="agent-name">{{ run.agentName || t('backstage.unknownAgent') }}</div>
                <div class="agent-owner" v-if="run.username">@{{ run.username }}</div>
              </div>
              <div class="breathing-dot-wrap" :title="dotTitle(run)">
                <span class="breathing-dot" :class="dotClass(run)"></span>
              </div>
            </div>

            <!-- Single human sentence -->
            <div class="agent-saying">{{ humanSentence(run) }}</div>

            <!-- Meta + soft progress, only when meaningful -->
            <div class="agent-meta-row">
              <span class="meta-time">{{ formatAge(run.ageMs) }}</span>
              <span v-if="run.subagentCount > 0" class="meta-pill">
                {{ t('backstage.subagentsBadge', { n: run.subagentCount }) }}
              </span>
              <span v-if="run.orphan && !run.stuckReason" class="meta-pill meta-pill-orphan" :title="t('backstage.orphanHint')">
                {{ t('backstage.orphan') }}
              </span>
            </div>
            <div class="agent-bar" v-if="showBar(run)">
              <div class="bar-fill" :style="progressFillStyle(run)"></div>
            </div>

            <!-- Foot: action hierarchy -->
            <div class="agent-card-foot">
              <button
                class="card-action card-action-soft"
                @click.stop="confirmStop(run)"
                :title="t('backstage.actions.stopHint')"
              >{{ t('backstage.actions.stop') }}</button>
              <button
                v-if="run.stuckReason"
                class="card-action card-action-strong"
                @click.stop="confirmRecycle(run)"
                :title="t('backstage.actions.endHint')"
              >{{ t('backstage.actions.endIt') }}</button>
            </div>
          </article>
        </div>
      </div>
    </div>

    <!-- Detail drawer -->
    <el-drawer
      v-model="drawerOpen"
      :show-close="true"
      :with-header="false"
      direction="rtl"
      size="440px"
    >
      <div v-if="detail" class="detail-pane">
        <header class="detail-header">
          <div class="agent-avatar detail-avatar" :style="avatarBgStyle(detail)">
            <SkillIcon
              v-if="detail.agentIcon"
              :value="detail.agentIcon"
              :size="42"
              fallback="🤖"
            />
            <span v-else class="agent-avatar-letter">{{ avatarLetter(detail) }}</span>
          </div>
          <div class="detail-title-block">
            <div class="detail-title">{{ detail.agentName || t('backstage.unknownAgent') }}</div>
            <div class="detail-subtitle" v-if="detail.username">@{{ detail.username }}</div>
          </div>
          <span class="breathing-dot detail-dot" :class="dotClass(detail)" :title="dotTitle(detail)"></span>
        </header>

        <p class="detail-saying">{{ humanSentence(detail) }}</p>

        <div v-if="detail.stuckReason" class="detail-callout">
          {{ stuckCallout(detail) }}
        </div>

        <dl class="detail-grid">
          <div class="detail-row">
            <dt>{{ t('backstage.detail.runningFor') }}</dt>
            <dd>{{ formatAge(detail.ageMs) }}</dd>
          </div>
          <div class="detail-row">
            <dt>{{ t('backstage.detail.lastHeard') }}</dt>
            <dd>{{ formatAge(detail.msSinceLastEvent) }} {{ t('backstage.detail.ago') }}</dd>
          </div>
          <div class="detail-row">
            <dt>{{ t('backstage.detail.audience') }}</dt>
            <dd>
              <span v-if="detail.subscriberCount === 0" class="detail-warn">
                {{ t('backstage.detail.noOneListening') }}
              </span>
              <span v-else>{{ t('backstage.detail.peopleListening', { n: detail.subscriberCount }) }}</span>
            </dd>
          </div>
        </dl>

        <div v-if="childrenOf(detail).length > 0" class="detail-section">
          <div class="detail-section-title">{{ t('backstage.detail.helpers') }}</div>
          <div v-for="sub in childrenOf(detail)" :key="sub.subagentId" class="subagent-row">
            <div class="subagent-icon" :style="avatarBgStyle(sub)">
              <SkillIcon v-if="sub.agentIcon" :value="sub.agentIcon" :size="20" fallback="🤖" />
              <span v-else class="agent-avatar-letter sub-letter">{{ avatarLetter(sub) }}</span>
            </div>
            <div class="subagent-body">
              <div class="subagent-name">{{ sub.agentName || sub.subagentId }}</div>
              <div class="subagent-meta">{{ sub.lastTool || sub.currentPhase || sub.status }} · {{ formatAge(sub.ageMs) }}</div>
            </div>
            <button class="subagent-stop" @click="confirmInterruptSub(sub)">{{ t('backstage.actions.stop') }}</button>
          </div>
        </div>

        <div class="detail-actions">
          <button class="btn-soft" @click="confirmStop(detail)">{{ t('backstage.actions.stop') }}</button>
          <button class="btn-strong" @click="confirmRecycle(detail)">{{ t('backstage.actions.endIt') }}</button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { backstageApi, type BackstageSnapshot, type BackstageRunCard, type BackstageSubagentCard } from '@/api'

const { t } = useI18n()

const snapshot = ref<BackstageSnapshot | null>(null)
const isInitialLoading = ref(true)
const autoRefresh = ref(true)
const drawerOpen = ref(false)
const detail = ref<BackstageRunCard | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const headlineMessage = computed(() => {
  if (!snapshot.value) return t('backstage.headline.loading')
  const s = snapshot.value.summary
  if (s.running === 0) return t('backstage.headline.allQuiet')
  if (s.stuck > 0) return t('backstage.headline.someoneNeedsAttention', { n: s.stuck })
  if (s.orphan > 0) return t('backstage.headline.workingAlone', { running: s.running, orphan: s.orphan })
  return t('backstage.headline.working', { n: s.running })
})

function avatarLetter(run: BackstageRunCard | BackstageSubagentCard): string {
  const name = run.agentName || (run as any).username || (run as any).subagentId || '?'
  return name.charAt(0).toUpperCase()
}

/**
 * Soft, low-saturation gradient seeded from agent name. Even when an icon
 * is present we use this as the avatar surface — Apple's wallpaper-behind-
 * icon trick, not a billboard.
 */
function avatarBgStyle(run: BackstageRunCard | BackstageSubagentCard) {
  const seed = run.agentName || (run as any).conversationId || (run as any).subagentId || 'x'
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0
  const hue = Math.abs(h) % 360
  return {
    background: `linear-gradient(140deg, hsl(${hue}, 55%, 92%), hsl(${(hue + 30) % 360}, 50%, 86%))`,
    color: `hsl(${hue}, 45%, 28%)`,
  }
}

function cardClass(run: BackstageRunCard) {
  return {
    'is-stuck': !!run.stuckReason,
    'is-orphan': run.orphan && !run.stuckReason,
    'is-healthy': !run.stuckReason && !run.orphan,
  }
}

function dotClass(run: BackstageRunCard | BackstageSubagentCard) {
  const r = run as BackstageRunCard
  if (r.stuckReason) return 'dot-stuck'
  if (r.orphan) return 'dot-orphan'
  return 'dot-healthy'
}

function dotTitle(run: BackstageRunCard | BackstageSubagentCard): string {
  const r = run as BackstageRunCard
  if (r.stuckReason) return t('backstage.dotTitle.stuck')
  if (r.orphan) return t('backstage.dotTitle.orphan')
  return t('backstage.dotTitle.healthy')
}

function humanSentence(run: BackstageRunCard): string {
  if (run.stuckReason === 'tool_silent') return t('backstage.saying.toolSilent', { tool: run.runningToolName || t('backstage.aTool') })
  if (run.stuckReason === 'idle_silent') return t('backstage.saying.idleSilent')
  if (run.stuckReason === 'hard_cap') return t('backstage.saying.hardCap')
  if (run.currentPhase === 'awaiting_approval') return t('backstage.saying.awaitingApproval')
  if (run.runningToolName) return t('backstage.saying.usingTool', { tool: run.runningToolName })
  if (run.currentPhase === 'executing_tool') return t('backstage.saying.usingSomething')
  if (run.currentPhase === 'summarizing') return t('backstage.saying.wrappingUp')
  if (run.currentPhase === 'planning') return t('backstage.saying.planning')
  if (!run.firstTokenReceived) return t('backstage.saying.thinking')
  return t('backstage.saying.replying')
}

function stuckCallout(run: BackstageRunCard): string {
  if (run.stuckReason === 'tool_silent') return t('backstage.callout.toolSilent', { tool: run.runningToolName || t('backstage.aTool'), time: formatAge(run.msSinceLastEvent) })
  if (run.stuckReason === 'idle_silent') return t('backstage.callout.idleSilent', { time: formatAge(run.msSinceLastEvent) })
  return t('backstage.callout.hardCap', { time: formatAge(run.ageMs) })
}

/**
 * Bar only appears once a run has been going long enough that elapsed time
 * starts to matter. Under 30s is "barely started" — rendering 1px of bar
 * adds visual noise without informing the user.
 */
function showBar(run: BackstageRunCard): boolean {
  return run.ageMs > 30_000
}

function progressFillStyle(run: BackstageRunCard) {
  // Map age to 0..100% over the 5-minute window. Beyond that we just stay full.
  const pct = Math.min(100, ((run.ageMs - 30_000) / 270_000) * 100)
  return { width: `${Math.max(4, pct)}%` }
}

function formatAge(ms: number): string {
  if (ms < 1500) return t('backstage.time.justNow')
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return t('backstage.time.seconds', { n: sec })
  const min = Math.floor(sec / 60)
  if (min < 60) return t('backstage.time.minutes', { n: min, s: sec % 60 })
  const hr = Math.floor(min / 60)
  return t('backstage.time.hours', { n: hr, m: min % 60 })
}

function childrenOf(run: BackstageRunCard): BackstageSubagentCard[] {
  return snapshot.value?.subagents.filter(s => s.parentConversationId === run.conversationId) ?? []
}

function openDetail(run: BackstageRunCard) {
  detail.value = run
  drawerOpen.value = true
}

async function refresh() {
  try {
    const res: any = await backstageApi.snapshot()
    snapshot.value = (res?.data ?? res) as BackstageSnapshot
    if (detail.value && snapshot.value) {
      const fresh = snapshot.value.runs.find(r => r.conversationId === detail.value!.conversationId)
      if (fresh) detail.value = fresh
    }
  } catch (e: any) {
    if (isInitialLoading.value) ElMessage.error(e?.message || t('backstage.errors.loadFailed'))
  } finally {
    isInitialLoading.value = false
  }
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    refresh()
    timer = setInterval(refresh, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function confirmStop(run: BackstageRunCard) {
  try {
    await ElMessageBox.confirm(
      t('backstage.confirm.stopBody', { name: run.agentName || t('backstage.unknownAgent') }),
      t('backstage.confirm.stopTitle'),
      { confirmButtonText: t('backstage.actions.stop'), cancelButtonText: t('common.cancel') }
    )
    await backstageApi.stop(run.conversationId)
    ElMessage.success(t('backstage.toast.stopped'))
    refresh()
  } catch { /* dismissed */ }
}

async function confirmRecycle(run: BackstageRunCard) {
  try {
    await ElMessageBox.confirm(
      t('backstage.confirm.endBody', { name: run.agentName || t('backstage.unknownAgent') }),
      t('backstage.confirm.endTitle'),
      {
        confirmButtonText: t('backstage.actions.endIt'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger',
      }
    )
    await backstageApi.recycle(run.conversationId)
    ElMessage.success(t('backstage.toast.ended'))
    drawerOpen.value = false
    refresh()
  } catch { /* dismissed */ }
}

async function confirmInterruptSub(sub: BackstageSubagentCard) {
  try {
    await ElMessageBox.confirm(
      t('backstage.confirm.subBody', { name: sub.agentName || sub.subagentId }),
      t('backstage.confirm.subTitle'),
      { confirmButtonText: t('backstage.actions.stop'), cancelButtonText: t('common.cancel') }
    )
    await backstageApi.interruptSubagent(sub.subagentId)
    ElMessage.success(t('backstage.toast.subStopped'))
    refresh()
  } catch { /* dismissed */ }
}

async function confirmSweep() {
  const stuckCount = snapshot.value?.summary.stuck ?? 0
  try {
    await ElMessageBox.confirm(
      t('backstage.confirm.sweepBody', { n: stuckCount }),
      t('backstage.confirm.sweepTitle'),
      {
        confirmButtonText: t('backstage.actions.tidyUp'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger',
      }
    )
    const res: any = await backstageApi.sweep()
    const recycled = res?.data?.recycled ?? 0
    ElMessage.success(t('backstage.toast.swept', { n: recycled }))
    refresh()
  } catch { /* dismissed */ }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.backstage-page {
  --card-radius: 24px;
}

/* ===== Header chips ===== */
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.chip-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.6);
  color: var(--mc-text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
  backdrop-filter: blur(8px);
}

html.dark .chip-btn {
  background: rgba(255, 255, 255, 0.04);
}

.chip-btn:hover {
  background: var(--mc-surface-overlay);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.chip-btn.is-paused {
  color: var(--mc-text-tertiary);
  opacity: 0.8;
}

.chip-pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: hsl(140, 55%, 50%);
  animation: chip-pulse 2.4s ease-in-out infinite;
}

@keyframes chip-pulse {
  0%, 100% { box-shadow: 0 0 0 0 hsla(140, 55%, 50%, 0.5); }
  50%      { box-shadow: 0 0 0 5px hsla(140, 55%, 50%, 0); }
}

.chip-btn-warm {
  background: linear-gradient(135deg, hsla(28, 90%, 60%, 0.14), hsla(20, 90%, 55%, 0.2));
  color: hsl(20, 70%, 40%);
  border-color: hsla(20, 80%, 55%, 0.3);
}

.chip-btn-warm:hover {
  background: linear-gradient(135deg, hsla(28, 90%, 60%, 0.22), hsla(20, 90%, 55%, 0.3));
  color: hsl(20, 75%, 35%);
}

/* ===== Cards grid ===== */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
}

.agent-card {
  padding: 24px;
  border-radius: var(--card-radius);
  cursor: pointer;
  position: relative;
  transition: transform 0.28s cubic-bezier(0.22, 0.61, 0.36, 1),
              box-shadow 0.28s ease,
              border-color 0.28s ease;
}

.agent-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 16px 48px -16px rgba(0, 0, 0, 0.16),
              0 4px 12px -4px rgba(0, 0, 0, 0.06);
}

.agent-card.is-stuck {
  border-color: hsla(20, 80%, 55%, 0.45);
  background: linear-gradient(180deg, hsla(28, 100%, 96%, 0.95), hsla(20, 100%, 92%, 0.98));
}

html.dark .agent-card.is-stuck {
  background: linear-gradient(180deg, hsla(20, 35%, 22%, 0.96), hsla(15, 30%, 18%, 0.98));
}

.agent-card.is-orphan {
  border-color: hsla(265, 50%, 60%, 0.28);
}

.agent-card-skeleton {
  cursor: default;
}

/* ===== Top row: avatar + name + dot ===== */
.agent-card-top {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

.agent-avatar {
  width: 50px;
  height: 50px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 19px;
  letter-spacing: -0.02em;
  flex-shrink: 0;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5),
              0 1px 2px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

html.dark .agent-avatar {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

.agent-avatar :deep(.skill-icon) {
  width: 100% !important;
  height: 100% !important;
  display: flex;
  align-items: center;
  justify-content: center;
}

.agent-avatar :deep(.skill-icon__glyph) {
  font-size: 28px;
  line-height: 1;
}

.agent-avatar :deep(.skill-icon__img) {
  width: 60%;
  height: 60%;
  object-fit: contain;
}

.agent-avatar :deep(.skill-icon__svg svg) {
  width: 60%;
  height: 60%;
}

.agent-avatar-letter {
  font-size: 19px;
  font-weight: 700;
}

.agent-id {
  flex: 1;
  min-width: 0;
}

.agent-name {
  font-weight: 600;
  font-size: 16px;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: -0.01em;
  line-height: 1.3;
}

.agent-owner {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-top: 3px;
  letter-spacing: 0.01em;
}

/* ===== Breathing dot ===== */
.breathing-dot-wrap {
  width: 14px;
  height: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.breathing-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: block;
  position: relative;
}

.breathing-dot.dot-healthy {
  background: hsl(155, 55%, 50%);
  animation: breathe-healthy 3s ease-in-out infinite;
}

.breathing-dot.dot-healthy::before {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 50%;
  background: hsla(155, 55%, 50%, 0.3);
  animation: breathe-pulse 3s ease-in-out infinite;
}

.breathing-dot.dot-stuck {
  background: hsl(20, 80%, 55%);
  animation: breathe-slow 4.5s ease-in-out infinite;
}

.breathing-dot.dot-stuck::before {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  background: hsla(20, 80%, 55%, 0.32);
  animation: breathe-pulse-slow 4.5s ease-in-out infinite;
}

.breathing-dot.dot-orphan {
  background: hsl(265, 55%, 62%);
  animation: breathe-healthy 3.5s ease-in-out infinite;
  opacity: 0.8;
}

@keyframes breathe-healthy {
  0%, 100% { opacity: 1;    transform: scale(1); }
  50%      { opacity: 0.7;  transform: scale(0.85); }
}

@keyframes breathe-slow {
  0%, 100% { opacity: 1;    transform: scale(1); }
  50%      { opacity: 0.5;  transform: scale(0.78); }
}

@keyframes breathe-pulse {
  0%, 100% { opacity: 0; transform: scale(0.85); }
  50%      { opacity: 1; transform: scale(1.5); }
}

@keyframes breathe-pulse-slow {
  0%, 100% { opacity: 0; transform: scale(0.8); }
  50%      { opacity: 1; transform: scale(1.7); }
}

/* ===== Saying ===== */
.agent-saying {
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--mc-text-secondary);
  min-height: 22px;
  margin-bottom: 16px;
  letter-spacing: -0.005em;
}

.agent-card.is-stuck .agent-saying {
  color: hsl(20, 70%, 35%);
  font-weight: 500;
}

html.dark .agent-card.is-stuck .agent-saying {
  color: hsl(28, 80%, 76%);
}

/* ===== Meta + bar ===== */
.agent-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-bottom: 10px;
}

.meta-time {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
}

.meta-pill {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  font-size: 11px;
  letter-spacing: 0.01em;
  border: 1px solid var(--mc-border-light);
}

.meta-pill-orphan {
  background: hsla(265, 50%, 60%, 0.1);
  color: hsl(265, 45%, 50%);
  border-color: hsla(265, 50%, 60%, 0.2);
}

.agent-bar {
  height: 2px;
  border-radius: 1px;
  background: var(--mc-bg-muted);
  overflow: hidden;
  margin-bottom: 14px;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, hsl(155, 55%, 65%), hsl(170, 50%, 55%));
  border-radius: 1px;
  transition: width 0.6s ease;
}

.is-stuck .bar-fill {
  background: linear-gradient(90deg, hsl(28, 80%, 60%), hsl(20, 80%, 55%));
}

/* ===== Foot ===== */
.agent-card-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 4px;
}

.card-action {
  padding: 6px 14px;
  font-size: 12.5px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.18s ease;
  font-weight: 500;
}

.card-action:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.card-action-strong {
  border-color: hsla(20, 80%, 55%, 0.5);
  color: hsl(20, 75%, 45%);
  background: hsla(20, 100%, 95%, 0.5);
}

html.dark .card-action-strong {
  background: hsla(20, 80%, 30%, 0.15);
}

.card-action-strong:hover {
  background: hsla(20, 80%, 55%, 0.12);
  color: hsl(20, 75%, 40%);
}

/* ===== Empty state ===== */
.empty-still {
  text-align: center;
  padding: 90px 20px;
}

.empty-orb {
  width: 72px;
  height: 72px;
  margin: 0 auto 24px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, hsla(155, 55%, 70%, 0.55), hsla(155, 55%, 50%, 0.18));
  animation: breathe-healthy 4s ease-in-out infinite;
}

.empty-line {
  font-size: 18px;
  font-weight: 500;
  color: var(--mc-text-primary);
  margin-bottom: 8px;
  letter-spacing: -0.01em;
}

.empty-hint {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* ===== Detail drawer ===== */
.detail-pane {
  padding: 28px 28px 24px;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}

.detail-avatar {
  width: 56px;
  height: 56px;
  border-radius: 18px;
}

.detail-avatar :deep(.skill-icon__glyph) {
  font-size: 32px;
}

.detail-avatar .agent-avatar-letter {
  font-size: 22px;
}

.detail-title-block {
  flex: 1;
  min-width: 0;
}

.detail-title {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.2;
}

.detail-subtitle {
  font-size: 12.5px;
  color: var(--mc-text-tertiary);
  margin-top: 2px;
}

.detail-dot {
  flex-shrink: 0;
}

.detail-saying {
  font-size: 15px;
  line-height: 1.6;
  color: var(--mc-text-primary);
  margin: 0 0 18px;
  letter-spacing: -0.005em;
}

.detail-warn {
  color: hsl(265, 50%, 50%);
}

.detail-callout {
  margin-bottom: 22px;
  padding: 14px 16px;
  border-radius: 14px;
  background: hsla(20, 100%, 96%, 0.9);
  color: hsl(20, 70%, 35%);
  font-size: 13px;
  line-height: 1.55;
  border: 1px solid hsla(20, 80%, 55%, 0.22);
}

html.dark .detail-callout {
  background: hsla(20, 35%, 18%, 0.7);
  color: hsl(28, 80%, 76%);
}

.detail-grid {
  margin: 0 0 24px;
  border-top: 1px solid var(--mc-border-light);
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 13px 0;
  border-bottom: 1px solid var(--mc-border-light);
  margin: 0;
}

.detail-row dt {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 0;
  letter-spacing: 0.01em;
}

.detail-row dd {
  font-size: 13.5px;
  color: var(--mc-text-primary);
  margin: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.detail-section {
  margin-top: 8px;
}

.detail-section-title {
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  margin-bottom: 10px;
  font-weight: 600;
}

.subagent-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--mc-bg-muted);
  margin-bottom: 6px;
}

.subagent-icon {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
  overflow: hidden;
}

.subagent-icon :deep(.skill-icon__glyph) {
  font-size: 18px;
}

.sub-letter {
  font-size: 13px;
}

.subagent-body {
  flex: 1;
  min-width: 0;
}

.subagent-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subagent-meta {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 1px;
}

.subagent-stop {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.6);
  font-size: 11px;
  cursor: pointer;
  color: var(--mc-text-secondary);
}

.subagent-stop:hover {
  background: var(--mc-surface-overlay);
}

.detail-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 28px;
}

.btn-soft,
.btn-strong {
  padding: 9px 20px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  border: none;
}

.btn-soft {
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  color: var(--mc-text-secondary);
}

.btn-soft:hover {
  background: var(--mc-surface-overlay);
  color: var(--mc-text-primary);
}

.btn-strong {
  background: linear-gradient(135deg, hsl(20, 80%, 56%), hsl(15, 80%, 50%));
  color: white;
  box-shadow: 0 4px 14px -4px hsla(20, 80%, 50%, 0.45);
}

.btn-strong:hover {
  box-shadow: 0 6px 20px -4px hsla(20, 80%, 50%, 0.55);
  transform: translateY(-1px);
}
</style>
