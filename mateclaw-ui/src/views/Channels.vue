<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner channels-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Connect</div>
            <h1 class="mc-page-title">{{ t('channels.title') }}</h1>
            <p class="mc-page-desc">{{ t('channels.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('channels.newChannel') }}
          </button>
        </div>

        <!-- 渠道卡片 -->
        <div class="channel-grid">
          <div v-for="channel in channels" :key="channel.id" class="channel-card mc-surface-card">
        <div class="channel-header">
          <div class="channel-icon-wrap">
            <img class="channel-icon-img" :src="getChannelIconPath(channel.channelType)" :alt="channel.channelType" />
          </div>
          <div class="channel-meta">
            <h3 class="channel-name">{{ channel.name }}</h3>
            <span class="channel-type">{{ channel.channelType }}</span>
          </div>
          <div class="channel-status-group">
            <div class="channel-status" :class="channel.enabled ? 'status-on' : 'status-off'">
              {{ channel.enabled ? t('channels.status.active') : t('channels.status.inactive') }}
            </div>
            <div
              v-if="channel.enabled"
              class="connection-indicator"
              :class="getConnectionClass(channel)"
              :title="getConnectionTooltip(channel)"
            >
              {{ getConnectionIcon(channel) }} {{ getConnectionLabel(channel) }}
            </div>
          </div>
        </div>
        <p class="channel-desc">{{ channel.description }}</p>
        <div class="channel-footer">
          <button class="card-btn" @click="openEditModal(channel)">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
            </svg>
            {{ t('channels.configure') }}
          </button>
          <button class="card-btn" @click="toggleChannel(channel)">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/>
              <line v-if="channel.enabled" x1="8" y1="12" x2="16" y2="12"/>
              <polyline v-else points="10 8 16 12 10 16"/>
            </svg>
            {{ channel.enabled ? t('channels.disable') : t('channels.enable') }}
          </button>
          <button class="card-btn danger" @click="deleteChannel(channel.id)">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6"/>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
            </svg>
            {{ t('common.delete') }}
          </button>
        </div>
          </div>

          <!-- 添加渠道卡片 -->
          <div class="channel-card add-card mc-surface-card" @click="openCreateModal">
            <div class="add-icon">+</div>
            <p class="add-label">{{ t('channels.addChannel') }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Modal -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editingChannel ? t('channels.modal.editTitle') : t('channels.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <!-- 基础信息 -->
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('channels.fields.name') }} <span class="required">*</span></label>
              <input v-model="form.name" class="form-input" :placeholder="t('channels.placeholders.name')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('channels.fields.type') }}</label>
              <select v-model="form.channelType" class="form-input" @change="onChannelTypeChange">
                <option value="web">{{ t('channels.types.web') }}</option>
                <option value="dingtalk">{{ t('channels.types.dingtalk') }}</option>
                <option value="feishu">{{ t('channels.types.feishu') }}</option>
                <option value="telegram">{{ t('channels.types.telegram') }}</option>
                <option value="discord">{{ t('channels.types.discord') }}</option>
                <option value="wecom">{{ t('channels.types.wecom') }}</option>
                <option value="weixin">{{ t('channels.types.weixin') }}</option>
                <option value="qq">{{ t('channels.types.qq') }}</option>
                <option value="slack">{{ t('channels.types.slack') }}</option>
                <option value="webchat">{{ t('channels.types.webchat') }}</option>
                <option value="webhook">{{ t('channels.types.webhook') }}</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('channels.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('channels.placeholders.description')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('channels.fields.bindAgent') }}</label>
              <select v-model="form.agentId" class="form-input">
                <option value="">{{ t('channels.placeholders.selectAgent') }}</option>
                <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                  {{ agent.icon || '🤖' }} {{ agent.name }}
                </option>
              </select>
            </div>
          </div>

          <!-- 配置区域：标签页 -->
          <div class="config-section">
            <div class="tab-bar">
              <button
                class="tab-btn" :class="{ active: configTab === 'form' }"
                @click="switchTab('form')"
              >{{ t('channels.tabs.form') }}</button>
              <button
                class="tab-btn" :class="{ active: configTab === 'json' }"
                @click="switchTab('json')"
              >{{ t('channels.tabs.json') }}</button>
            </div>

            <!-- 表单配置 Tab -->
            <div v-if="configTab === 'form'" class="tab-content">
              <!-- 接入引导卡片 -->
              <div v-if="webhookGuide" class="guide-card">
                <div v-if="needsWebhookUrl" class="guide-webhook-row">
                  <span class="guide-label">Webhook URL</span>
                  <code class="guide-url">{{ webhookUrl }}</code>
                  <button type="button" class="copy-btn" @click="copyWebhookUrl" :title="t('common.copy')">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                    </svg>
                    {{ copyLabel }}
                  </button>
                </div>
                <div v-if="needsWebhookUrl && isLocalhost" class="guide-warn">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                    <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                  </svg>
                  {{ t('channels.webhook.localhostWarn') }}
                </div>
                <ol class="guide-steps">
                  <li v-for="(step, i) in webhookGuide.steps" :key="i" v-html="step"></li>
                </ol>
              </div>

              <!-- 微信 iLink Bot 扫码登录 -->
              <div v-if="form.channelType === 'weixin'" class="weixin-auth-card">
                <p class="weixin-auth-hint">
                  {{ t('channels.weixin.authHint') }}
                </p>
                <button type="button" class="weixin-auth-btn" @click="handleWeixinQrcode" :disabled="weixinQrcodeLoading">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                    <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                    <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                    <line x1="21" y1="21" x2="21" y2="21"/>
                  </svg>
                  {{ weixinQrcodeLoading ? t('channels.weixin.qrcodeLoading') : t('channels.weixin.qrcodeButton') }}
                </button>
                <!-- 二维码展示区 -->
                <div v-if="weixinQrcodeImg || weixinPollStatus === 'confirmed'" class="weixin-qrcode-area">
                  <img v-if="weixinQrcodeImg" :src="weixinQrcodeImg" alt="WeChat QR Code" class="weixin-qrcode-img" />
                  <p class="weixin-scan-hint" :class="weixinPollStatus">
                    <template v-if="weixinPollStatus === 'scanned'">{{ t('channels.weixin.scanned') }}</template>
                    <template v-else-if="weixinPollStatus === 'confirmed'">{{ t('channels.weixin.loginSuccess') }}</template>
                    <template v-else-if="weixinPollStatus === 'expired'">{{ t('channels.weixin.qrcodeExpired') }}</template>
                    <template v-else>{{ t('channels.weixin.scanHint') }}</template>
                  </p>
                </div>
              </div>

              <!-- 企业微信扫码授权 -->
              <div v-if="form.channelType === 'wecom'" class="wecom-auth-card">
                <p class="wecom-auth-hint">
                  {{ t('channels.wecom.authHint') }}
                </p>
                <button type="button" class="wecom-auth-btn" @click="handleWecomAuth" :disabled="wecomAuthLoading">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                    <rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="3" height="3"/>
                    <line x1="21" y1="14" x2="21" y2="17"/><line x1="14" y1="21" x2="17" y2="21"/>
                    <line x1="21" y1="21" x2="21" y2="21"/>
                  </svg>
                  {{ wecomAuthLoading ? t('channels.wecom.authLoading') : t('channels.wecom.authButton') }}
                </button>
              </div>

              <!-- 渠道专属配置字段 -->
              <template v-if="currentFieldDefs.length > 0">
                <div class="form-grid">
                  <div
                    v-for="field in currentFieldDefs" :key="field.key"
                    class="form-group"
                    :class="{ 'full-width': field.type === 'text' && (field.placeholder?.length || 0) > 30 }"
                  >
                    <label class="form-label">
                      {{ field.label }}
                      <span v-if="field.required" class="required">*</span>
                      <span v-if="field.tooltip" class="tooltip-icon" :title="field.tooltip">?</span>
                    </label>

                    <!-- 密码字段：带显示/隐藏切换 -->
                    <div v-if="field.sensitive || field.type === 'password'" class="password-wrap">
                      <input
                        v-model="channelConfig[field.key]"
                        :type="visibleFields[field.key] ? 'text' : 'password'"
                        class="form-input"
                        :placeholder="field.placeholder"
                        :readonly="field.readOnly"
                        autocomplete="off"
                      />
                      <button
                        v-if="!field.readOnly"
                        type="button" class="eye-btn"
                        @click="visibleFields[field.key] = !visibleFields[field.key]"
                        :title="visibleFields[field.key] ? t('common.hide') : t('common.show')"
                      >
                        <svg v-if="visibleFields[field.key]" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                          <circle cx="12" cy="12" r="3"/>
                        </svg>
                        <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                          <line x1="1" y1="1" x2="23" y2="23"/>
                        </svg>
                      </button>
                      <button
                        v-else-if="channelConfig[field.key]"
                        type="button" class="copy-inline-btn"
                        @click="copyText(String(channelConfig[field.key]))"
                        :title="t('common.copy')"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                        </svg>
                      </button>
                    </div>

                    <!-- 下拉选择 -->
                    <select
                      v-else-if="field.type === 'select'"
                      v-model="channelConfig[field.key]"
                      class="form-input"
                    >
                      <option v-for="opt in field.options" :key="opt.value" :value="opt.value">
                        {{ opt.label }}
                      </option>
                    </select>

                    <!-- 开关 -->
                    <div v-else-if="field.type === 'switch'" class="switch-wrap">
                      <label class="switch">
                        <input type="checkbox" v-model="channelConfig[field.key]" />
                        <span class="switch-slider"></span>
                      </label>
                      <span class="switch-label">{{ channelConfig[field.key] ? t('common.on') : t('common.off') }}</span>
                    </div>

                    <!-- 数字 -->
                    <input
                      v-else-if="field.type === 'number'"
                      v-model.number="channelConfig[field.key]"
                      type="number"
                      class="form-input"
                      :placeholder="field.placeholder"
                      :readonly="field.readOnly"
                    />

                    <!-- 普通文本 -->
                    <input
                      v-else
                      v-model="channelConfig[field.key]"
                      type="text"
                      class="form-input"
                      :placeholder="field.placeholder"
                      :readonly="field.readOnly"
                    />

                    <span v-if="field.readOnly && field.key === 'api_key'" class="form-hint">
                      {{ editingChannel ? t('channels.webchatApiKeyReadOnly') : t('channels.webchatApiKeyGenerated') }}
                    </span>
                  </div>
                </div>
              </template>

              <!-- 飞书所需权限提示（根据开关动态计算） -->
              <div v-if="form.channelType === 'feishu'" class="permission-hints">
                <div class="permission-header">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                  </svg>
                  <span>{{ t('channels.feishuPermissions.title') }}</span>
                  <a
                    :href="feishuPermissionUrl"
                    target="_blank" rel="noopener"
                    class="permission-link"
                  >{{ t('channels.feishuPermissions.goToPermissions') }}</a>
                </div>
                <div class="permission-list">
                  <div v-for="perm in feishuRequiredPermissions" :key="perm.scope" class="permission-item">
                    <code class="permission-scope">{{ perm.scope }}</code>
                    <span class="permission-desc">{{ perm.desc }}</span>
                    <span class="permission-reason">{{ perm.reason }}</span>
                  </div>
                </div>
              </div>

              <!-- Web 类型：无额外配置 -->
              <div v-else-if="form.channelType === 'web'" class="empty-config">
                <p class="empty-text">{{ t('channels.webHint') }}</p>
              </div>

              <!-- WebChat 类型 -->
              <div v-else-if="form.channelType === 'webchat'" class="empty-config">
                <p class="empty-text">{{ t('channels.webchatHint') }}</p>
              </div>

              <!-- Webhook 类型 -->
              <div v-else-if="form.channelType === 'webhook'" class="empty-config">
                <p class="empty-text">{{ t('channels.webhookHint') }}</p>
              </div>

              <!-- 高级配置（折叠） -->
              <div class="advanced-section">
                <button class="advanced-toggle" @click="showAdvanced = !showAdvanced">
                  <svg
                    width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                    :style="{ transform: showAdvanced ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }"
                  >
                    <polyline points="9 18 15 12 9 6"/>
                  </svg>
                  {{ t('channels.advanced') }}
                </button>
                <div v-if="showAdvanced" class="advanced-body">
                  <!-- 访问控制 -->
                  <div class="form-group full-width section-divider">
                    <label class="section-label">{{ t('channels.accessControl.title') }}</label>
                  </div>
                  <div class="form-grid">
                    <div class="form-group">
                      <label class="form-label">
                        {{ t('channels.accessControl.dmPolicy') }}
                        <span class="tooltip-icon" :title="t('channels.accessControl.dmPolicyTooltip')">?</span>
                      </label>
                      <select v-model="accessControl.dm_policy" class="form-input">
                        <option value="open">{{ t('channels.accessControl.policyOpen') }}</option>
                        <option value="closed">{{ t('channels.accessControl.policyClosed') }}</option>
                      </select>
                    </div>
                    <div class="form-group">
                      <label class="form-label">
                        {{ t('channels.accessControl.groupPolicy') }}
                        <span class="tooltip-icon" :title="t('channels.accessControl.groupPolicyTooltip')">?</span>
                      </label>
                      <select v-model="accessControl.group_policy" class="form-input">
                        <option value="open">{{ t('channels.accessControl.policyOpen') }}</option>
                        <option value="closed">{{ t('channels.accessControl.policyClosed') }}</option>
                      </select>
                    </div>
                    <div class="form-group full-width">
                      <label class="form-label">
                        {{ t('channels.accessControl.allowFrom') }}
                        <span class="tooltip-icon" :title="t('channels.accessControl.allowFromTooltip')">?</span>
                      </label>
                      <input v-model="accessControl.allow_from" class="form-input" :placeholder="t('channels.accessControl.allowFromPlaceholder')" />
                    </div>
                    <div class="form-group">
                      <label class="form-label">{{ t('channels.accessControl.denyMessage') }}</label>
                      <input v-model="accessControl.deny_message" class="form-input" :placeholder="t('channels.accessControl.denyMessagePlaceholder')" />
                    </div>
                    <div class="form-group">
                      <label class="form-label">
                        {{ t('channels.accessControl.requireMention') }}
                        <span class="tooltip-icon" :title="t('channels.accessControl.requireMentionTooltip')">?</span>
                      </label>
                      <select v-model="accessControl.require_mention" class="form-input">
                        <option :value="true">{{ t('common.yes') }}</option>
                        <option :value="false">{{ t('common.no') }}</option>
                      </select>
                    </div>
                  </div>

                  <!-- 消息过滤 -->
                  <div class="form-group full-width section-divider">
                    <label class="section-label">{{ t('channels.messageFilter.title') }}</label>
                  </div>
                  <div class="form-grid">
                    <div class="form-group">
                      <label class="form-label">
                        {{ t('channels.messageFilter.filterThinking') }}
                        <span class="tooltip-icon" :title="t('channels.messageFilter.filterThinkingTooltip')">?</span>
                      </label>
                      <select v-model="renderConfig.filter_thinking" class="form-input">
                        <option :value="true">{{ t('common.yes') }}</option>
                        <option :value="false">{{ t('common.no') }}</option>
                      </select>
                    </div>
                    <div class="form-group">
                      <label class="form-label">
                        {{ t('channels.messageFilter.filterToolMessages') }}
                        <span class="tooltip-icon" :title="t('channels.messageFilter.filterToolMessagesTooltip')">?</span>
                      </label>
                      <select v-model="renderConfig.filter_tool_messages" class="form-input">
                        <option :value="true">{{ t('common.yes') }}</option>
                        <option :value="false">{{ t('common.no') }}</option>
                      </select>
                    </div>
                    <div class="form-group">
                      <label class="form-label">{{ t('channels.messageFilter.messageFormat') }}</label>
                      <select v-model="renderConfig.message_format" class="form-input">
                        <option value="auto">{{ t('channels.messageFilter.formatAuto') }}</option>
                        <option value="markdown">{{ t('channels.messageFilter.formatMarkdown') }}</option>
                        <option value="text">{{ t('channels.messageFilter.formatText') }}</option>
                        <option value="html">{{ t('channels.messageFilter.formatHtml') }}</option>
                      </select>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 原始 JSON Tab -->
            <div v-if="configTab === 'json'" class="tab-content">
              <p class="json-hint">{{ t('channels.jsonHint') }}</p>
              <textarea
                v-model="rawConfigJson"
                class="form-textarea json-editor"
                rows="14"
                placeholder='{"client_id": "...", "client_secret": "..."}'
                spellcheck="false"
              ></textarea>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveChannel" :disabled="!form.name">{{ t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { channelApi, agentApi } from '@/api/index'
import { CHANNEL_FIELD_DEFS } from '@/types/index'
import type { Channel, Agent, ChannelFieldDef } from '@/types/index'

const { t } = useI18n()
const channels = ref<Channel[]>([])
const agents = ref<Agent[]>([])
const showModal = ref(false)
const editingChannel = ref<Channel | null>(null)

/** 渠道连接状态映射 */
const channelStatusMap = ref<Record<string | number, {
  connectionState: string
  lastError: string | null
  reconnectAttempts: number
}>>({})

let statusPollTimer: ReturnType<typeof setInterval> | null = null

// ==================== 表单状态 ====================

const defaultForm = () => ({
  name: '', channelType: 'web' as string, description: '', configJson: '', agentId: null as string | number | null, enabled: true,
})
const form = ref(defaultForm())

/** 渠道专属配置（从 configJson 中提取的字段值） */
const channelConfig = ref<Record<string, any>>({})

/** 敏感字段可见性 */
const visibleFields = ref<Record<string, boolean>>({})

/** 配置标签页 */
const configTab = ref<'form' | 'json'>('form')

/** 原始 JSON 编辑内容 */
const rawConfigJson = ref('')

/** 高级配置折叠状态 */
const showAdvanced = ref(false)

const defaultAccessControl = () => ({
  dm_policy: 'open' as string,
  group_policy: 'open' as string,
  allow_from: '',
  deny_message: '',
  require_mention: false as boolean,
})
const accessControl = ref(defaultAccessControl())

const defaultRenderConfig = () => ({
  filter_thinking: true as boolean,
  filter_tool_messages: true as boolean,
  message_format: 'auto' as string,
})
const renderConfig = ref(defaultRenderConfig())

/** 当前渠道类型的字段定义（过滤条件显示字段） */
const currentFieldDefs = computed<ChannelFieldDef[]>(() => {
  const allFields = CHANNEL_FIELD_DEFS[form.value.channelType] || []
  return allFields.filter(field => {
    if (!field.showIf) return true
    return channelConfig.value[field.showIf.field] === field.showIf.value
  })
})

// ==================== Webhook 引导 ====================

const WEBHOOK_TYPES = new Set(['dingtalk', 'feishu', 'telegram', 'discord', 'wecom', 'weixin', 'qq'])

interface WebhookGuideInfo {
  steps: string[]
}

const WEBHOOK_GUIDES = computed<Record<string, WebhookGuideInfo>>(() => ({
  dingtalk: {
    steps: [
      t('channels.guide.dingtalk.step1'),
      t('channels.guide.dingtalk.step2'),
      t('channels.guide.dingtalk.step3'),
    ],
  },
  feishu: {
    steps: [
      t('channels.guide.feishu.step1'),
      t('channels.guide.feishu.step2'),
      t('channels.guide.feishu.step3'),
      t('channels.guide.feishu.step4'),
      t('channels.guide.feishu.step5'),
    ],
  },
  telegram: {
    steps: [
      t('channels.guide.telegram.step1'),
      t('channels.guide.telegram.step2'),
      t('channels.guide.telegram.step3'),
      t('channels.guide.telegram.step4'),
    ],
  },
  discord: {
    steps: [
      t('channels.guide.discord.step1'),
      t('channels.guide.discord.step2'),
      t('channels.guide.discord.step3'),
      t('channels.guide.discord.step4'),
      t('channels.guide.discord.step5'),
    ],
  },
  wecom: {
    steps: [
      t('channels.guide.wecom.step1'),
      t('channels.guide.wecom.step2'),
      t('channels.guide.wecom.step3'),
      t('channels.guide.wecom.step4'),
    ],
  },
  weixin: {
    steps: [
      t('channels.guide.weixin.step1'),
      t('channels.guide.weixin.step2'),
      t('channels.guide.weixin.step3'),
      t('channels.guide.weixin.step4'),
    ],
  },
  qq: {
    steps: [
      t('channels.guide.qq.step1'),
      t('channels.guide.qq.step2'),
      t('channels.guide.qq.step3'),
      t('channels.guide.qq.step4'),
    ],
  },
}))

/** 当前渠道是否有接入引导 */
const webhookGuide = computed<WebhookGuideInfo | null>(() => {
  return WEBHOOK_GUIDES.value[form.value.channelType] || null
})

/** 当前渠道是否需要 Webhook URL（WebSocket / Stream / Long-Polling 模式不需要） */
const needsWebhookUrl = computed(() => {
  const type = form.value.channelType
  // 企业微信、QQ、微信使用 WebSocket 长连接，不需要 Webhook URL
  if (type === 'wecom' || type === 'qq' || type === 'weixin') return false
  // Discord 使用 Gateway WebSocket，不需要 Webhook URL
  if (type === 'discord') return false
  // 钉钉 Stream 模式不需要 Webhook URL
  if (type === 'dingtalk' && channelConfig.value.connection_mode !== 'webhook') return false
  // 飞书 WebSocket 模式不需要 Webhook URL
  if (type === 'feishu' && channelConfig.value.connection_mode === 'websocket') return false
  // Telegram Long-Polling 模式不需要 Webhook URL
  if (type === 'telegram' && channelConfig.value.connection_mode !== 'webhook') return false
  return true
})

// ==================== 企业微信扫码授权 ====================

const WECOM_SDK_URL = 'https://wwcdn.weixin.qq.com/node/wework/js/wecom-aibot-sdk@0.1.0.min.js'
const WECOM_SOURCE = 'mateclaw'

/** SDK 是否已加载 */
let wecomSdkLoaded = false

/** 扫码授权加载中 */
const wecomAuthLoading = ref(false)

/** 动态加载企业微信 JS SDK */
function loadWecomSDK(): Promise<void> {
  return new Promise((resolve, reject) => {
    if ((window as any).WecomAIBotSDK || wecomSdkLoaded) {
      resolve()
      return
    }
    const script = document.createElement('script')
    script.src = WECOM_SDK_URL
    script.async = true
    script.onload = () => {
      wecomSdkLoaded = true
      resolve()
    }
    script.onerror = () => reject(new Error('WeCom SDK 加载失败'))
    document.body.appendChild(script)
  })
}

/** 处理企业微信扫码授权 */
async function handleWecomAuth() {
  wecomAuthLoading.value = true
  try {
    await loadWecomSDK()
  } catch {
    ElMessage.error(t('channels.wecom.sdkFailed'))
    wecomAuthLoading.value = false
    return
  }

  const sdk = (window as any).WecomAIBotSDK
  if (!sdk) {
    ElMessage.error(t('channels.wecom.sdkFailed'))
    wecomAuthLoading.value = false
    return
  }

  wecomAuthLoading.value = false

  const result = sdk.openBotInfoAuthWindow({
    source: WECOM_SOURCE,
  })

  if (result && typeof result.then === 'function') {
    result.then(
      (bot: { botid: string; secret: string }) => {
        if (bot?.botid) {
          channelConfig.value.bot_id = bot.botid
          channelConfig.value.secret = bot.secret
          ElMessage.success(t('channels.wecom.authSuccess'))
        }
      },
      (error: { code: string; message: string }) => {
        if (error?.code === 'WINDOW_BLOCKED') {
          ElMessage.error(t('channels.wecom.windowBlocked'))
        } else if (error?.code === 'CANCELLED') {
          ElMessage.info(t('channels.wecom.authCancelled'))
        } else {
          ElMessage.error(t('channels.wecom.authFailed') + '：' + (error?.message || error?.code || ''))
        }
      },
    )
  }
}

// ==================== 微信 iLink Bot 扫码登录 ====================

/** 二维码图片（base64 或 URL） */
const weixinQrcodeImg = ref('')
/** 二维码加载中 */
const weixinQrcodeLoading = ref(false)
/** 轮询状态：polling / scanned / confirmed / expired */
const weixinPollStatus = ref<string>('')
/** 轮询定时器 */
let weixinPollTimer: ReturnType<typeof setInterval> | null = null
/** 防止重复确认 */
let weixinConfirmed = false

/** 停止轮询 */
function stopWeixinPoll() {
  if (weixinPollTimer) {
    clearInterval(weixinPollTimer)
    weixinPollTimer = null
  }
}

/** 处理获取微信登录二维码 */
async function handleWeixinQrcode() {
  weixinQrcodeLoading.value = true
  weixinQrcodeImg.value = ''
  weixinPollStatus.value = ''
  weixinConfirmed = false
  stopWeixinPoll()

  try {
    const data: any = await channelApi.weixinQrcode()
    const imgContent = data?.qrcode_img_content || data?.qrcode_img || ''
    const qrcodeId = data?.qrcode || ''

    if (!imgContent && !qrcodeId) {
      ElMessage.error(t('channels.weixin.qrcodeFailed'))
      weixinQrcodeLoading.value = false
      return
    }

    // 处理图片：如果是 URL 直接使用，否则包装为 data URI
    if (imgContent) {
      weixinQrcodeImg.value = imgContent.startsWith('http')
        ? imgContent
        : `data:image/png;base64,${imgContent}`
    }

    weixinQrcodeLoading.value = false
    weixinPollStatus.value = 'polling'

    // 开始轮询扫码状态（每 2 秒）
    if (qrcodeId) {
      weixinPollTimer = setInterval(async () => {
        try {
          const s: any = await channelApi.weixinQrcodeStatus(qrcodeId)
          const status = s?.status || ''

          if (status === 'scanned') {
            weixinPollStatus.value = 'scanned'
          }

          if (status === 'confirmed' && s?.bot_token) {
            if (weixinConfirmed) return
            weixinConfirmed = true
            stopWeixinPoll()
            // 自动填入 bot_token
            channelConfig.value.bot_token = s.bot_token
            // 如果返回了 base_url，也更新
            if (s.base_url) {
              channelConfig.value.base_url = s.base_url
            }
            weixinQrcodeImg.value = ''
            weixinPollStatus.value = 'confirmed'
            ElMessage.success(t('channels.weixin.loginSuccess'))
          }

          if (status === 'expired') {
            stopWeixinPoll()
            weixinQrcodeImg.value = ''
            weixinPollStatus.value = 'expired'
            ElMessage.warning(t('channels.weixin.qrcodeExpired'))
          }
        } catch {
          // 轮询错误静默忽略
        }
      }, 2000)
    }
  } catch (e: any) {
    ElMessage.error(t('channels.weixin.qrcodeFailed'))
    weixinQrcodeLoading.value = false
  }
}

/** 是否在本地开发环境 */
const isLocalhost = computed(() => {
  const host = window.location.hostname
  return host === 'localhost' || host === '127.0.0.1' || host === '0.0.0.0'
})

/** 生成 Webhook URL */
const webhookUrl = computed(() => {
  const proto = window.location.protocol
  const host = window.location.hostname
  // 生产环境跟随当前页面端口，开发环境提示用户替换
  const port = window.location.port
  const portSuffix = port ? `:${port}` : ''
  return `${proto}//${host}${portSuffix}/api/v1/channels/webhook/${form.value.channelType}`
})

/** 飞书权限管理页面 URL（需要 app_id） */
const feishuPermissionUrl = computed(() => {
  const appId = channelConfig.value?.app_id || ''
  const domain = channelConfig.value?.domain === 'lark' ? 'open.larksuite.com' : 'open.feishu.cn'
  if (appId) {
    return `https://${domain}/app/${appId}/permission`
  }
  return `https://${domain}/`
})

/** 根据飞书功能开关动态计算所需权限 */
const feishuRequiredPermissions = computed(() => {
  const perms: { scope: string; desc: string; reason: string }[] = []

  perms.push(
    { scope: 'im:message', desc: t('channels.feishu.perm.message'), reason: t('channels.feishu.perm.messageReason') },
    { scope: 'im:message.receive_v1', desc: t('channels.feishu.perm.receive'), reason: t('channels.feishu.perm.receiveReason') },
  )

  if (channelConfig.value?.connection_mode === 'websocket') {
    perms.push(
      { scope: 'im:resource', desc: t('channels.feishu.perm.resource'), reason: t('channels.feishu.perm.resourceReason') },
    )
  }

  if (channelConfig.value?.enable_reaction !== false) {
    perms.push(
      { scope: 'im:message.reactions', desc: t('channels.feishu.perm.reactions'), reason: t('channels.feishu.perm.reactionsReason') },
    )
  }

  if (channelConfig.value?.enable_nickname_cache !== false) {
    perms.push(
      { scope: 'contact:user.base:readonly', desc: t('channels.feishu.perm.contact'), reason: t('channels.feishu.perm.contactReason') },
    )
  }

  if (channelConfig.value?.media_download_enabled) {
    perms.push(
      { scope: 'im:message.resource', desc: t('channels.feishu.perm.media'), reason: t('channels.feishu.perm.mediaReason') },
    )
  }

  return perms
})

const copyLabel = ref(t('channels.webhook.copy'))

async function copyWebhookUrl() {
  try {
    await navigator.clipboard.writeText(webhookUrl.value)
    copyLabel.value = t('channels.webhook.copied')
    setTimeout(() => { copyLabel.value = t('channels.webhook.copy') }, 2000)
  } catch {
    ElMessage.warning(t('channels.webhook.copyFailed'))
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('common.copied'))
  } catch {
    ElMessage.warning(t('channels.webhook.copyFailed'))
  }
}

// ==================== 生命周期 ====================

onMounted(async () => {
  await loadChannels()
  await loadStatus()
  const res: any = await agentApi.list()
  agents.value = res.data || []
  statusPollTimer = setInterval(loadStatus, 10000)
})

onUnmounted(() => {
  if (statusPollTimer) {
    clearInterval(statusPollTimer)
    statusPollTimer = null
  }
})

onBeforeUnmount(() => {
  stopWeixinPoll()
})

// ==================== 数据加载 ====================

async function loadChannels() {
  try {
    const res: any = await channelApi.list()
    channels.value = (res.data || []).map((c: any) => ({
      ...c,
    }))
  } catch (e: any) {
    ElMessage.error(t('channels.messages.loadFailed') + ': ' + (e?.message || ''))
    channels.value = []
  }
}

async function loadStatus() {
  try {
    const res: any = await channelApi.status()
    const statusData = res.data
    if (statusData && Array.isArray(statusData.channels)) {
      const map: Record<number, any> = {}
      for (const ch of statusData.channels) {
        map[ch.id] = {
          connectionState: ch.connectionState || 'DISCONNECTED',
          lastError: ch.lastError || null,
          reconnectAttempts: ch.reconnectAttempts || 0,
        }
      }
      channelStatusMap.value = map
    }
  } catch {
    // 静默处理
  }
}

// ==================== 连接状态 ====================

function getConnectionState(channel: Channel): string {
  return channelStatusMap.value[channel.id]?.connectionState || 'DISCONNECTED'
}

function getConnectionIcon(channel: Channel): string {
  const state = getConnectionState(channel)
  switch (state) {
    case 'CONNECTED': return '🟢'
    case 'RECONNECTING': return '🟡'
    case 'ERROR': return '🔴'
    default: return '⚪'
  }
}

function getConnectionLabel(channel: Channel): string {
  const state = getConnectionState(channel)
  switch (state) {
    case 'CONNECTED': return t('channels.connection.connected')
    case 'RECONNECTING': return t('channels.connection.reconnecting')
    case 'ERROR': return t('channels.connection.error')
    case 'DISCONNECTED': return t('channels.connection.disconnected')
    default: return state
  }
}

function getConnectionClass(channel: Channel): string {
  const state = getConnectionState(channel)
  switch (state) {
    case 'CONNECTED': return 'conn-connected'
    case 'RECONNECTING': return 'conn-reconnecting'
    case 'ERROR': return 'conn-error'
    default: return 'conn-disconnected'
  }
}

function getConnectionTooltip(channel: Channel): string {
  const status = channelStatusMap.value[channel.id]
  if (!status) return ''
  let tip = getConnectionLabel(channel)
  if (status.reconnectAttempts > 0) {
    tip += ` (${t('channels.connection.retryCount', { n: status.reconnectAttempts })})`
  }
  if (status.lastError) {
    tip += `\n${t('channels.connection.errorLabel')}: ${status.lastError}`
  }
  return tip
}

// ==================== 弹窗操作 ====================

function openCreateModal() {
  editingChannel.value = null
  form.value = defaultForm()
  channelConfig.value = {}
  visibleFields.value = {}
  accessControl.value = defaultAccessControl()
  renderConfig.value = defaultRenderConfig()
  rawConfigJson.value = ''
  configTab.value = 'form'
  showAdvanced.value = false
  initDefaultFieldValues()
  showModal.value = true
}

function openEditModal(channel: Channel) {
  editingChannel.value = channel
  form.value = { ...channel } as any
  configTab.value = 'form'
  showAdvanced.value = false
  visibleFields.value = {}

  // 从 configJson 反序列化
  const cfg = parseConfigJson(channel.configJson)
  channelConfig.value = extractChannelFields(cfg, channel.channelType)
  accessControl.value = extractAccessControl(cfg)
  renderConfig.value = extractRenderConfig(cfg)
  rawConfigJson.value = channel.configJson || ''
  showModal.value = true
}

function closeModal() {
  showModal.value = false
  editingChannel.value = null
}

/** 渠道类型变更时重置配置字段 */
function onChannelTypeChange() {
  channelConfig.value = {}
  visibleFields.value = {}
  // 清理微信扫码状态
  stopWeixinPoll()
  weixinQrcodeImg.value = ''
  weixinPollStatus.value = ''
  initDefaultFieldValues()
}

/** 用字段定义中的 defaultValue 初始化 */
function initDefaultFieldValues() {
  const fields = CHANNEL_FIELD_DEFS[form.value.channelType] || []
  for (const f of fields) {
    if (f.defaultValue !== undefined && channelConfig.value[f.key] === undefined) {
      channelConfig.value[f.key] = f.defaultValue
    }
  }
}

/** 切换标签页时同步数据 */
function switchTab(tab: 'form' | 'json') {
  if (tab === 'json' && configTab.value === 'form') {
    // 表单 -> JSON：序列化表单值到 rawConfigJson
    rawConfigJson.value = buildConfigJson()
  } else if (tab === 'form' && configTab.value === 'json') {
    // JSON -> 表单：解析 rawConfigJson 回填到表单
    const cfg = parseConfigJson(rawConfigJson.value)
    channelConfig.value = extractChannelFields(cfg, form.value.channelType)
    accessControl.value = extractAccessControl(cfg)
    renderConfig.value = extractRenderConfig(cfg)
  }
  configTab.value = tab
}

// ==================== 保存 ====================

async function saveChannel() {
  try {
    const payload = { ...form.value }

    if (configTab.value === 'json') {
      // 当前在 JSON 标签页，校验 JSON 格式后使用
      if (rawConfigJson.value.trim()) {
        try {
          JSON.parse(rawConfigJson.value)
        } catch {
          ElMessage.error(t('channels.messages.invalidJson'))
          return
        }
      }
      payload.configJson = rawConfigJson.value
    } else {
      // 表单模式：将所有字段合并为 configJson
      payload.configJson = buildConfigJson()
    }

    if (editingChannel.value) {
      await channelApi.update(editingChannel.value.id, payload)
    } else {
      await channelApi.create(payload)
    }
    closeModal()
    await loadChannels()
  } catch (e: any) { ElMessage.error(e?.message || t('channels.messages.saveFailed')) }
}

/** 将表单字段合并为完整的 configJson 字符串 */
function buildConfigJson(): string {
  const cfg: Record<string, any> = {}

  // 渠道专属字段
  const fields = CHANNEL_FIELD_DEFS[form.value.channelType] || []
  for (const f of fields) {
    const val = channelConfig.value[f.key]
    if (val !== undefined && val !== '' && val !== null) {
      cfg[f.key] = val
    }
  }

  // 访问控制
  cfg.dm_policy = accessControl.value.dm_policy
  cfg.group_policy = accessControl.value.group_policy
  cfg.allow_from = accessControl.value.allow_from
    ? accessControl.value.allow_from.split(/[,，]/).map((s: string) => s.trim()).filter(Boolean)
    : []
  cfg.deny_message = accessControl.value.deny_message
  cfg.require_mention = accessControl.value.require_mention

  // 消息过滤
  cfg.filter_thinking = renderConfig.value.filter_thinking
  cfg.filter_tool_messages = renderConfig.value.filter_tool_messages
  cfg.message_format = renderConfig.value.message_format

  return JSON.stringify(cfg, null, 2)
}

// ==================== 配置解析工具 ====================

function parseConfigJson(json?: string): Record<string, any> {
  if (!json) return {}
  try { return JSON.parse(json) } catch { return {} }
}

/** 从 config 对象提取渠道专属字段 */
function extractChannelFields(cfg: Record<string, any>, channelType: string): Record<string, any> {
  const fields = CHANNEL_FIELD_DEFS[channelType] || []
  const result: Record<string, any> = {}

  // 兼容旧 Telegram 配置：如果有 webhook_url 但没有 connection_mode，推断为 webhook
  if (channelType === 'telegram' && cfg.webhook_url && !cfg.connection_mode) {
    cfg = { ...cfg, connection_mode: 'webhook' }
  }

  for (const f of fields) {
    if (cfg[f.key] !== undefined) {
      result[f.key] = cfg[f.key]
    } else if (f.defaultValue !== undefined) {
      result[f.key] = f.defaultValue
    }
  }
  return result
}

function extractAccessControl(cfg: Record<string, any>) {
  const defaults = defaultAccessControl()
  return {
    dm_policy: cfg.dm_policy || defaults.dm_policy,
    group_policy: cfg.group_policy || defaults.group_policy,
    allow_from: Array.isArray(cfg.allow_from) ? cfg.allow_from.join(', ') : (cfg.allow_from || ''),
    deny_message: cfg.deny_message || defaults.deny_message,
    require_mention: cfg.require_mention === true,
  }
}

function extractRenderConfig(cfg: Record<string, any>) {
  const defaults = defaultRenderConfig()
  return {
    filter_thinking: cfg.filter_thinking !== false,
    filter_tool_messages: cfg.filter_tool_messages !== false,
    message_format: cfg.message_format || defaults.message_format,
  }
}

// ==================== CRUD ====================

async function deleteChannel(id: string | number) {
  try { await ElMessageBox.confirm(t('channels.messages.deleteConfirm'), t('channels.messages.deleteTitle'), { type: 'warning' }) } catch { return }
  try {
    await channelApi.delete(id)
    await loadChannels()
  } catch (e: any) { ElMessage.error(e?.message || t('channels.messages.deleteFailed')) }
}

async function toggleChannel(channel: Channel) {
  try {
    await channelApi.toggle(channel.id, !channel.enabled)
    await loadChannels()
    await loadStatus()
  } catch (e: any) { ElMessage.error(e?.message || t('channels.messages.toggleFailed')) }
}

// ==================== 渠道图标 ====================

const CHANNEL_ICON_TYPES = ['web', 'dingtalk', 'feishu', 'wecom', 'weixin', 'telegram', 'discord', 'qq', 'slack', 'webchat', 'webhook']
function getChannelIconPath(type: string) {
  const name = CHANNEL_ICON_TYPES.includes(type) ? type : 'default'
  return `/icons/channels/${name}.svg`
}
</script>

<style scoped>
.channels-page { gap: 18px; }

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* 渠道卡片 */
.channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
.channel-card { padding: 20px; transition: all 0.15s; min-height: 238px; display: flex; flex-direction: column; }
.channel-card:hover { border-color: var(--mc-primary-light); box-shadow: var(--mc-shadow-medium); transform: translateY(-2px); }
.channel-header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.channel-icon-wrap { width: 48px; height: 48px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; overflow: hidden; background: linear-gradient(135deg, rgba(217,109,87,0.12), rgba(24,74,69,0.08)); }
.channel-icon-img { width: 42px; height: 42px; border-radius: 12px; object-fit: cover; }
.channel-meta { flex: 1; }
.channel-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; }
.channel-type { font-size: 12px; color: var(--mc-text-tertiary); }

.channel-status { padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 500; }
.channel-status-group { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.status-on { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-off { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.connection-indicator { font-size: 11px; padding: 2px 8px; border-radius: 12px; white-space: nowrap; cursor: default; }
.conn-connected { color: var(--mc-primary); background: var(--mc-primary-bg); }
.conn-reconnecting { color: var(--mc-primary-hover); background: var(--mc-primary-bg); animation: pulse-reconnecting 1.5s ease-in-out infinite; }
.conn-error { color: var(--mc-danger); background: var(--mc-danger-bg); }
.conn-disconnected { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
@keyframes pulse-reconnecting { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.channel-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 14px; line-height: 1.6; min-height: 42px; }
.channel-footer { display: flex; gap: 6px; border-top: 1px solid var(--mc-border-light); padding-top: 12px; margin-top: auto; flex-wrap: wrap; }
.card-btn { display: flex; align-items: center; gap: 4px; padding: 7px 11px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.card-btn:hover { background: var(--mc-bg-sunken); }
.card-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

.add-card { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 238px; border: 2px dashed var(--mc-border); cursor: pointer; background: transparent; }
.add-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.add-icon { font-size: 28px; color: var(--mc-text-tertiary); margin-bottom: 8px; }
.add-label { font-size: 14px; color: var(--mc-text-tertiary); }
.add-card:hover .add-icon, .add-card:hover .add-label { color: var(--mc-primary); }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border-radius: 16px; width: 100%; max-width: 620px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

/* Form */
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-primary); display: flex; align-items: center; gap: 4px; }
.required { color: var(--mc-danger, #ef4444); font-size: 13px; }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-elevated); width: 100%; box-sizing: border-box; }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-textarea { resize: vertical; font-family: monospace; }

/* Tooltip */
.tooltip-icon { display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px; border-radius: 50%; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); font-size: 10px; font-weight: 700; cursor: help; flex-shrink: 0; }

/* 配置区域 */
.config-section { margin-top: 20px; }

/* 标签页 */
.tab-bar { display: flex; gap: 0; border-bottom: 1px solid var(--mc-border-light); margin-bottom: 16px; }
.tab-btn { padding: 8px 16px; background: none; border: none; border-bottom: 2px solid transparent; font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.tab-btn:hover { color: var(--mc-text-primary); }
.tab-btn.active { color: var(--mc-primary); border-bottom-color: var(--mc-primary); }
.tab-content { min-height: 60px; }

/* Webhook 引导卡片 */
.guide-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.guide-webhook-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.guide-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.5px; flex-shrink: 0; }
.guide-url { font-size: 12px; font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 5px; padding: 3px 8px; color: var(--mc-text-primary); word-break: break-all; flex: 1; min-width: 0; }
.copy-btn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 5px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; white-space: nowrap; flex-shrink: 0; }
.copy-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.guide-warn { display: flex; align-items: flex-start; gap: 6px; padding: 8px 10px; background: rgba(234, 179, 8, 0.08); border: 1px solid rgba(234, 179, 8, 0.2); border-radius: 6px; font-size: 12px; color: var(--mc-text-secondary); margin-bottom: 8px; line-height: 1.5; }
.guide-warn svg { flex-shrink: 0; color: #eab308; margin-top: 1px; }
.guide-warn a { color: var(--mc-primary); text-decoration: none; }
.guide-warn a:hover { text-decoration: underline; }
.guide-steps { margin: 0; padding-left: 20px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.7; }
.guide-steps li { margin-bottom: 2px; }
.guide-steps :deep(a) { color: var(--mc-primary); text-decoration: none; }
.guide-steps :deep(a:hover) { text-decoration: underline; }
.guide-steps :deep(code) { font-size: 12px; background: var(--mc-bg-sunken); padding: 1px 5px; border-radius: 3px; }

/* 企业微信扫码授权卡片 */
.wecom-auth-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.wecom-auth-hint { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.wecom-auth-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: var(--mc-primary, #D97757); color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.wecom-auth-btn:hover:not(:disabled) { background: var(--mc-primary-hover, #C1572B); transform: translateY(-1px); box-shadow: 0 2px 8px rgba(217,119,87,0.3); }
.wecom-auth-btn:active:not(:disabled) { transform: translateY(0); }
.wecom-auth-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* 微信 iLink Bot 扫码登录 */
.weixin-auth-card { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.2)); border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.weixin-auth-hint { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px 0; line-height: 1.6; }
.weixin-auth-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 10px 16px; background: #07C160; color: #fff; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; }
.weixin-auth-btn:hover:not(:disabled) { background: #06AD56; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(7,193,96,0.3); }
.weixin-auth-btn:active:not(:disabled) { transform: translateY(0); }
.weixin-auth-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.weixin-qrcode-area { display: flex; flex-direction: column; align-items: center; margin-top: 16px; padding: 16px; background: #fff; border-radius: 8px; border: 1px solid var(--mc-border); }
.weixin-qrcode-img { width: 200px; height: 200px; border-radius: 4px; }
.weixin-scan-hint { font-size: 13px; color: var(--mc-text-secondary); margin-top: 10px; transition: color 0.2s; }
.weixin-scan-hint.scanned { color: #E6A23C; }
.weixin-scan-hint.confirmed { color: #07C160; font-weight: 500; }
.weixin-scan-hint.expired { color: #F56C6C; }

.guide-steps :deep(b) { color: var(--mc-text-primary); font-weight: 600; }

/* 飞书权限提示 */
.permission-hints { background: var(--mc-primary-bg, rgba(217,119,87,0.06)); border: 1px solid var(--mc-primary-light, rgba(217,119,87,0.15)); border-radius: 10px; padding: 12px 16px; margin-top: 12px; margin-bottom: 8px; }
.permission-header { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 10px; }
.permission-header svg { color: var(--mc-primary); flex-shrink: 0; }
.permission-link { margin-left: auto; font-size: 12px; font-weight: 500; color: var(--mc-primary); text-decoration: none; white-space: nowrap; }
.permission-link:hover { text-decoration: underline; }
.permission-list { display: flex; flex-direction: column; gap: 6px; }
.permission-item { display: flex; align-items: baseline; gap: 8px; font-size: 12px; line-height: 1.5; }
.permission-scope { font-size: 11px; font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 4px; padding: 1px 6px; color: var(--mc-primary); white-space: nowrap; flex-shrink: 0; }
.permission-desc { color: var(--mc-text-primary); flex-shrink: 0; }
.permission-reason { color: var(--mc-text-tertiary, var(--mc-text-secondary)); font-size: 11px; opacity: 0.7; }

/* 密码字段 */
.password-wrap { position: relative; display: flex; align-items: center; }
.password-wrap .form-input { padding-right: 36px; }
.eye-btn { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--mc-text-tertiary); padding: 2px; display: flex; align-items: center; }
.eye-btn:hover { color: var(--mc-text-primary); }
.copy-inline-btn { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--mc-text-tertiary); padding: 2px; display: flex; align-items: center; }
.copy-inline-btn:hover { color: var(--mc-text-primary); }
.form-hint { font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }

/* 开关 */
.switch-wrap { display: flex; align-items: center; gap: 8px; height: 36px; }
.switch { position: relative; display: inline-block; width: 36px; height: 20px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch-slider { position: absolute; cursor: pointer; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.switch-slider::before { content: ""; position: absolute; height: 14px; width: 14px; left: 3px; bottom: 3px; background: white; border-radius: 50%; transition: 0.2s; }
.switch input:checked + .switch-slider { background: var(--mc-primary); }
.switch input:checked + .switch-slider::before { transform: translateX(16px); }
.switch-label { font-size: 13px; color: var(--mc-text-secondary); }

/* 空配置 */
.empty-config { padding: 24px 16px; text-align: center; }
.empty-text { font-size: 13px; color: var(--mc-text-tertiary); margin: 0; }

/* 高级配置 */
.advanced-section { margin-top: 20px; border-top: 1px solid var(--mc-border-light); padding-top: 12px; }
.advanced-toggle { display: flex; align-items: center; gap: 6px; background: none; border: none; cursor: pointer; font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); padding: 4px 0; }
.advanced-toggle:hover { color: var(--mc-text-primary); }
.advanced-body { margin-top: 12px; }

.section-divider { margin-top: 8px; padding-top: 12px; border-top: 1px solid var(--mc-border-light); }
.section-label { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }

/* JSON 编辑器 */
.json-hint { font-size: 12px; color: var(--mc-text-tertiary); margin: 0 0 8px; }
.json-editor { font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace; font-size: 13px; line-height: 1.5; tab-size: 2; }
</style>
