<template>
  <Teleport to="body">
    <Transition name="focus-fade">
      <div v-if="open && run" class="focus-backdrop" @click.self="$emit('close')">
        <div class="focus-panel mc-surface-card" role="dialog" aria-modal="true">
          <button
            class="focus-close"
            type="button"
            :aria-label="t('backstage.actions.close')"
            @click="$emit('close')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2.5" stroke-linecap="round" aria-hidden="true">
              <path d="M6 6 L18 18 M18 6 L6 18"/>
            </svg>
          </button>

          <!-- Hero: avatar (with status ring) + name + handle -->
          <div class="focus-hero">
            <div class="agent-avatar-wrap focus-avatar-wrap" :class="ringClass(run)" :title="dotTitle(run)">
              <div class="agent-avatar focus-avatar" :style="avatarBgStyle(run)">
                <SkillIcon
                  v-if="run.agentIcon"
                  :value="run.agentIcon"
                  :size="48"
                  fallback="🤖"
                />
                <span v-else class="agent-avatar-letter focus-avatar-letter">{{ avatarLetter(run) }}</span>
              </div>
            </div>
            <div class="focus-title">{{ run.agentName || t('backstage.unknownAgent') }}</div>
            <div class="focus-subtitle" v-if="run.username">@{{ run.username }}</div>
          </div>

          <!-- The one sentence: what it's doing right now -->
          <p class="focus-saying">{{ humanSentence(run) }}</p>

          <!-- Stuck callout -->
          <div v-if="run.stuckReason" class="focus-callout">
            {{ stuckCallout(run) }}
          </div>

          <!-- Time ring: 5-min activity window, center digits -->
          <div class="time-ring" :class="{ 'time-ring-warn': !!run.stuckReason }">
            <svg viewBox="0 0 120 120" class="ring-svg" aria-hidden="true">
              <circle cx="60" cy="60" r="54" class="ring-track"/>
              <circle
                cx="60"
                cy="60"
                r="54"
                class="ring-fill"
                :style="{
                  strokeDasharray: RING_CIRCUMFERENCE,
                  strokeDashoffset: ringDashoffset(run.ageMs),
                }"
              />
            </svg>
            <div class="ring-label">
              <div class="ring-value">{{ formatAge(run.ageMs) }}</div>
              <div class="ring-caption">{{ t('backstage.detail.runningFor') }}</div>
            </div>
          </div>

          <!-- Secondary metadata -->
          <dl class="focus-grid">
            <div class="focus-row">
              <dt>{{ t('backstage.detail.lastHeard') }}</dt>
              <dd>{{ formatAge(run.msSinceLastEvent) }} {{ t('backstage.detail.ago') }}</dd>
            </div>
            <div class="focus-row">
              <dt>{{ t('backstage.detail.audience') }}</dt>
              <dd>
                <span v-if="run.subscriberCount === 0" class="focus-warn">
                  {{ t('backstage.detail.noOneListening') }}
                </span>
                <span v-else>{{ t('backstage.detail.peopleListening', { n: run.subscriberCount }) }}</span>
              </dd>
            </div>
            <div class="focus-row">
              <dt>{{ t('backstage.detail.session') }}</dt>
              <dd class="focus-id" :title="run.conversationId">#{{ shortId(run.conversationId) }}</dd>
            </div>
          </dl>

          <!-- Helpers (subagents) -->
          <div v-if="subagents.length > 0" class="focus-section">
            <div class="focus-section-title">{{ t('backstage.detail.helpers') }}</div>
            <div v-for="sub in subagents" :key="sub.subagentId" class="focus-sub-row">
              <div class="focus-sub-icon" :style="avatarBgStyle(sub)">
                <SkillIcon v-if="sub.agentIcon" :value="sub.agentIcon" :size="20" fallback="🤖" />
                <span v-else class="focus-sub-letter">{{ avatarLetter(sub) }}</span>
              </div>
              <div class="focus-sub-body">
                <div class="focus-sub-name">{{ sub.agentName || sub.subagentId }}</div>
                <div class="focus-sub-meta">
                  {{ sub.lastTool || sub.currentPhase || sub.status }} · {{ formatAge(sub.ageMs) }}
                </div>
              </div>
              <button class="focus-sub-stop" type="button" @click="$emit('interrupt-sub', sub)">
                {{ t('backstage.actions.stop') }}
              </button>
            </div>
          </div>

          <div class="focus-actions">
            <button class="focus-btn focus-btn-soft" type="button" @click="$emit('stop', run)">
              {{ t('backstage.actions.stop') }}
            </button>
            <button class="focus-btn focus-btn-strong" type="button" @click="$emit('recycle', run)">
              {{ t('backstage.actions.endIt') }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { useBackstageAgent } from '@/composables/useBackstageAgent'
import type { BackstageRunCard, BackstageSubagentCard } from '@/api'

const props = defineProps<{
  open: boolean
  run: BackstageRunCard | null
  subagents: BackstageSubagentCard[]
}>()

const emit = defineEmits<{
  close: []
  stop: [run: BackstageRunCard]
  recycle: [run: BackstageRunCard]
  'interrupt-sub': [sub: BackstageSubagentCard]
}>()

const { t } = useI18n()
const {
  avatarLetter,
  avatarBgStyle,
  ringClass,
  dotTitle,
  humanSentence,
  stuckCallout,
  formatAge,
} = useBackstageAgent()

// SVG ring geometry: r=54 gives a circumference of ~339.292.
// Map the agent's age over a 5-minute window to a stroke-dashoffset so the
// ring visibly fills as time passes — Apple-watch activity ring metaphor.
// Beyond 5 minutes the ring just stays full; the colour shifts to warn when
// the agent is stuck so "full ring + warn" reads as "this has been at it
// long enough that you should look at it".
const RING_CIRCUMFERENCE = 2 * Math.PI * 54

function ringDashoffset(ageMs: number): number {
  const p = Math.min(1, ageMs / 300_000)
  return RING_CIRCUMFERENCE * (1 - p)
}

function shortId(id: string): string {
  return id.length <= 8 ? id : id.slice(-8)
}

// Esc closes the panel — keyboard parity with the click-on-backdrop affordance.
function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) emit('close')
}

// Lock the body's scroll while the panel is open. The backdrop already
// catches scroll, but the underlying page would otherwise lurch when the
// modal is dismissed because grid changed in the meantime.
watch(
  () => props.open,
  open => {
    if (typeof document === 'undefined') return
    document.body.style.overflow = open ? 'hidden' : ''
  },
)

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  if (typeof document !== 'undefined') document.body.style.overflow = ''
})
</script>

<style scoped>
/* ===== Backdrop + entry/leave ===== */
.focus-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(20, 14, 10, 0.42);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  overflow-y: auto;
}

html.dark .focus-backdrop {
  background: rgba(0, 0, 0, 0.5);
}

.focus-fade-enter-active,
.focus-fade-leave-active {
  transition: opacity 0.22s ease, backdrop-filter 0.22s ease, -webkit-backdrop-filter 0.22s ease;
}

.focus-fade-enter-active .focus-panel,
.focus-fade-leave-active .focus-panel {
  transition: transform 0.28s cubic-bezier(0.22, 0.61, 0.36, 1),
              opacity 0.22s ease;
}

.focus-fade-enter-from,
.focus-fade-leave-to {
  opacity: 0;
  backdrop-filter: blur(0px);
  -webkit-backdrop-filter: blur(0px);
}

.focus-fade-enter-from .focus-panel,
.focus-fade-leave-to .focus-panel {
  opacity: 0;
  transform: scale(0.96) translateY(8px);
}

/* ===== Panel ===== */
.focus-panel {
  position: relative;
  width: 100%;
  max-width: 520px;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  padding: 36px 32px 28px;
  border-radius: 24px;
  box-shadow: 0 32px 80px -20px rgba(0, 0, 0, 0.35),
              0 8px 24px -8px rgba(0, 0, 0, 0.18);
}

.focus-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: none;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.18s ease, color 0.18s ease;
  padding: 0;
}

.focus-close:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

/* ===== Hero ===== */
.focus-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-bottom: 22px;
  padding-top: 4px;
}

.focus-avatar-wrap {
  /* Inherits .agent-avatar-wrap from below; this just bumps the ring inset
     to match the larger 64px focus avatar. */
}

.focus-avatar-wrap::before {
  inset: 0;
  border-radius: 23px; /* focus avatar 20 + 3 outset */
}

.focus-avatar {
  width: 64px;
  height: 64px;
  border-radius: 20px;
}

.focus-avatar :deep(.skill-icon__glyph) {
  font-size: 38px;
}

.focus-avatar-letter {
  font-size: 26px;
}

.focus-title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--mc-text-primary);
  text-align: center;
  margin-top: 8px;
  line-height: 1.2;
}

.focus-subtitle {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* ===== The one sentence ===== */
.focus-saying {
  font-size: 22px;
  line-height: 1.45;
  font-weight: 500;
  letter-spacing: -0.015em;
  color: var(--mc-text-primary);
  text-align: center;
  margin: 0 0 24px;
}

.focus-callout {
  margin-bottom: 24px;
  padding: 14px 16px;
  border-radius: 14px;
  background: hsla(20, 100%, 96%, 0.9);
  color: hsl(20, 70%, 35%);
  font-size: 13px;
  line-height: 1.55;
  border: 1px solid hsla(20, 80%, 55%, 0.22);
  text-align: center;
}

html.dark .focus-callout {
  background: hsla(20, 35%, 18%, 0.7);
  color: hsl(28, 80%, 76%);
}

/* ===== Time ring =====
 * Using brand tokens so the dial belongs to the panel instead of looking
 * like a green sticker glued on. Healthy = mc-accent (deep teal in light,
 * mint in dark). Stuck = mc-primary (terracotta). The inner glow is an
 * accent-soft radial fill on a ::before, not an extra SVG element — keeps
 * the template untouched.
 */
.time-ring {
  width: 132px;
  height: 132px;
  position: relative;
  margin: 4px auto 28px;
}

.time-ring::before {
  content: '';
  position: absolute;
  inset: 10px;
  border-radius: 50%;
  background: radial-gradient(
    circle at center,
    var(--mc-accent-soft) 0%,
    rgba(220, 232, 228, 0) 72%
  );
  pointer-events: none;
  transition: background 0.3s ease;
  z-index: 0;
}

.time-ring-warn::before {
  background: radial-gradient(
    circle at center,
    hsla(20, 100%, 92%, 0.7) 0%,
    hsla(20, 100%, 92%, 0) 72%
  );
}

html.dark .time-ring::before {
  background: radial-gradient(
    circle at center,
    rgba(92, 166, 157, 0.14) 0%,
    rgba(92, 166, 157, 0) 72%
  );
}

html.dark .time-ring-warn::before {
  background: radial-gradient(
    circle at center,
    rgba(235, 143, 101, 0.16) 0%,
    rgba(235, 143, 101, 0) 72%
  );
}

.ring-svg {
  position: relative;
  width: 100%;
  height: 100%;
  transform: rotate(-90deg); /* start from 12 o'clock */
  display: block;
  z-index: 1;
  filter: drop-shadow(0 1px 2px rgba(28, 20, 16, 0.05));
}

.ring-track,
.ring-fill {
  fill: none;
  stroke-width: 4;
  stroke-linecap: round;
}

.ring-track {
  stroke: var(--mc-border-light);
  opacity: 0.7;
}

.ring-fill {
  stroke: var(--mc-accent);
  transition: stroke-dashoffset 0.6s ease, stroke 0.3s ease;
}

.time-ring-warn .ring-fill {
  stroke: var(--mc-primary);
}

.ring-label {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  z-index: 2;
  /* Reserve a centered column so wider Chinese strings ("13 分 39 秒")
     don't crash into the ring; we let them shrink rather than wrap. */
  padding: 0 14px;
}

.ring-value {
  font-family: var(--mc-font-body), ui-sans-serif, system-ui, -apple-system, sans-serif;
  font-size: 17px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.005em;
  color: var(--mc-text-primary);
  line-height: 1.1;
  white-space: nowrap;
  text-align: center;
}

.ring-caption {
  font-size: 10px;
  color: var(--mc-text-tertiary);
  letter-spacing: 0.06em;
  margin-top: 6px;
  font-weight: 500;
}

/* ===== Secondary grid ===== */
.focus-grid {
  margin: 0 0 8px;
  border-top: 1px solid var(--mc-border-light);
}

.focus-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 13px 0;
  border-bottom: 1px solid var(--mc-border-light);
  margin: 0;
}

.focus-row dt {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 0;
  letter-spacing: 0.01em;
}

.focus-row dd {
  font-size: 13.5px;
  color: var(--mc-text-primary);
  margin: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.focus-warn {
  color: hsl(265, 50%, 50%);
}

html.dark .focus-warn {
  color: hsl(265, 55%, 70%);
}

.focus-id {
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0.02em;
  color: var(--mc-text-tertiary);
}

/* ===== Subagents ===== */
.focus-section {
  margin-top: 16px;
}

.focus-section-title {
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  margin-bottom: 10px;
  font-weight: 600;
}

.focus-sub-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--mc-bg-muted);
  margin-bottom: 6px;
}

.focus-sub-icon {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
  overflow: hidden;
  font-weight: 700;
}

.focus-sub-icon :deep(.skill-icon__glyph) {
  font-size: 18px;
}

.focus-sub-letter {
  font-size: 13px;
}

.focus-sub-body {
  flex: 1;
  min-width: 0;
}

.focus-sub-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.focus-sub-meta {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 1px;
}

.focus-sub-stop {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.6);
  font-size: 11px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  font-family: inherit;
}

.focus-sub-stop:hover {
  background: var(--mc-surface-overlay);
  color: var(--mc-text-primary);
}

html.dark .focus-sub-stop {
  background: rgba(255, 255, 255, 0.04);
}

/* ===== Action footer ===== */
.focus-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 24px;
}

.focus-btn {
  padding: 9px 20px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease, color 0.2s ease;
  border: none;
  font-family: inherit;
}

.focus-btn-soft {
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  color: var(--mc-text-secondary);
}

.focus-btn-soft:hover {
  background: var(--mc-surface-overlay);
  color: var(--mc-text-primary);
}

.focus-btn-strong {
  background: linear-gradient(135deg, hsl(20, 80%, 56%), hsl(15, 80%, 50%));
  color: white;
  box-shadow: 0 4px 14px -4px hsla(20, 80%, 50%, 0.45);
}

.focus-btn-strong:hover {
  box-shadow: 0 6px 20px -4px hsla(20, 80%, 50%, 0.55);
  transform: translateY(-1px);
}

/* ===== Status ring (parent + child share via composable; styles repeated
   here because Vue scoped styles isolate per-component. Keyframe names get
   auto-prefixed by Vue's scoped-style transform, so this won't collide
   with the parent's identical declaration). ===== */
.agent-avatar-wrap {
  position: relative;
  flex-shrink: 0;
  display: inline-flex;
  padding: 4px;
  margin: -4px;
}

.agent-avatar-wrap::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: 19px;
  border: 2px solid transparent;
  pointer-events: none;
  z-index: 0;
  will-change: transform, opacity;
}

.agent-avatar-wrap.ring-healthy::before {
  border-color: hsl(155, 55%, 50%);
  animation: focus-ring-breathe 3s ease-in-out infinite;
}

.agent-avatar-wrap.ring-stuck::before {
  border-color: hsl(20, 80%, 55%);
  animation: focus-ring-breathe-slow 6s ease-in-out infinite;
}

.agent-avatar-wrap.ring-orphan::before {
  border-color: hsla(265, 50%, 60%, 0.6);
  animation: focus-ring-breathe-faint 8s ease-in-out infinite;
}

.agent-avatar-wrap.ring-thinking::before {
  border-color: transparent;
  border-top-color: hsl(155, 55%, 55%);
  border-right-color: hsla(155, 55%, 55%, 0.4);
  animation: focus-ring-spin 1.6s linear infinite;
}

html.dark .agent-avatar-wrap.ring-healthy::before { border-color: hsl(155, 55%, 60%); }
html.dark .agent-avatar-wrap.ring-stuck::before   { border-color: hsl(20, 75%, 62%); }
html.dark .agent-avatar-wrap.ring-orphan::before  { border-color: hsla(265, 55%, 70%, 0.7); }
html.dark .agent-avatar-wrap.ring-thinking::before {
  border-top-color: hsl(155, 55%, 65%);
  border-right-color: hsla(155, 55%, 65%, 0.4);
}

@keyframes focus-ring-breathe {
  0%, 100% { opacity: 0.85; transform: scale(1); }
  50%      { opacity: 0.35; transform: scale(1.06); }
}

@keyframes focus-ring-breathe-slow {
  0%, 100% { opacity: 1;   transform: scale(1); }
  50%      { opacity: 0.55; transform: scale(1.07); }
}

@keyframes focus-ring-breathe-faint {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50%      { opacity: 0.3; transform: scale(1.015); }
}

@keyframes focus-ring-spin {
  to { transform: rotate(360deg); }
}

/* ===== Avatar surface (matches parent) ===== */
.agent-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
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

.agent-avatar :deep(.skill-icon__img) {
  width: 60%;
  height: 60%;
  object-fit: contain;
}

.agent-avatar :deep(.skill-icon__svg svg) {
  width: 60%;
  height: 60%;
}

/* ===== Mobile ===== */
@media (max-width: 600px) {
  .focus-panel {
    max-width: 100%;
    border-radius: 20px;
    padding: 28px 22px 24px;
    max-height: calc(100vh - 32px);
  }
  .focus-saying {
    font-size: 19px;
  }
  .focus-title {
    font-size: 20px;
  }
  .time-ring {
    width: 116px;
    height: 116px;
  }
  .ring-value {
    font-size: 15px;
  }
}
</style>
