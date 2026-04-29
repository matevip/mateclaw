package vip.mate.channel.feishu;

import com.lark.oapi.scene.registration.AccessDeniedException;
import com.lark.oapi.scene.registration.ExpiredException;
import com.lark.oapi.scene.registration.QRCodeInfo;
import com.lark.oapi.scene.registration.RegisterApp;
import com.lark.oapi.scene.registration.RegisterAppOptions;
import com.lark.oapi.scene.registration.RegisterAppResult;
import com.lark.oapi.scene.registration.StatusChangeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书"一键应用注册"服务
 * <p>
 * 包装 oapi-sdk 2.6.x 的 {@link RegisterApp#register} 流程：用户扫码后飞书侧自动建好
 * 自建应用并把 client_id/client_secret 回传，省掉用户手动到开放平台建应用 + 复制 ID/Secret
 * 的步骤。
 * <p>
 * SDK 的 register() 是阻塞的（内部轮询直到扫码确认或超时），所以这里用一个工作线程执行，
 * QR URL 通过 onQRCode 回调写到 session，前端按 token 轮询 status 端点查最终结果。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class FeishuAppRegistrationService {

    /** 会话最长存活时间：5 分钟（QR 自身约 3 分钟过期，留足缓冲让前端读到 expired 状态） */
    private static final long SESSION_TTL_MS = 5 * 60_000L;

    /** session_id → 注册会话状态 */
    private final ConcurrentHashMap<String, RegistrationSession> sessions = new ConcurrentHashMap<>();

    /**
     * 启动一次注册流程：spawn 后台线程跑 SDK register()，立即返回 sessionId。
     * QR URL 在 onQRCode 回调里被写入 session，调用方需要轮询 {@link #getSession(String)} 拿。
     *
     * @param domainKey "feishu"（国内）或 "lark"（国际） —— 仅用作记录，
     *                  SDK 内部会把 domain/larkDomain 都试一遍（QR 二维码扫码端
     *                  本来就支持飞书 / Lark 两种 App 互通），所以这里**不传**
     *                  域名让 SDK 自动用默认 accounts.feishu.cn / accounts.larksuite.com。
     *                  之前误传开放平台域名 https://open.feishu.cn 会让 SDK 拿到
     *                  HTML 当 JSON 解析直接 invalid_response。
     * @return 新创建的 sessionId，前端拿这个去查询 QR / status
     */
    public String begin(String domainKey) {
        evictExpiredSessions();

        String sessionId = UUID.randomUUID().toString();
        RegistrationSession session = new RegistrationSession(sessionId);
        sessions.put(sessionId, session);

        // 不传 .domain()/.larkDomain()：SDK 默认用 accounts.feishu.cn 和 accounts.larksuite.com
        // 这两个是注册账号端点，open.feishu.cn 是开放 API 端点 —— 完全不是一个东西
        RegisterAppOptions options = RegisterAppOptions.newBuilder()
                .source("mateclaw")
                .onQRCode(qr -> session.onQRCode(qr))
                .onStatusChange(status -> session.onStatusChange(status))
                .build();

        Thread worker = new Thread(() -> {
            try {
                RegisterAppResult result = RegisterApp.register(options);
                session.onSuccess(result);
            } catch (ExpiredException e) {
                session.onTerminal(Status.EXPIRED, "QR code expired");
            } catch (AccessDeniedException e) {
                session.onTerminal(Status.DENIED, "User denied authorization");
            } catch (Exception e) {
                log.warn("[feishu-register] register() failed: {}", e.getMessage());
                session.onTerminal(Status.ERROR, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }, "feishu-register-" + sessionId.substring(0, 8));
        worker.setDaemon(true);
        worker.start();

        return sessionId;
    }

    public RegistrationSession getSession(String sessionId) {
        evictExpiredSessions();
        return sessions.get(sessionId);
    }

    /**
     * Drop sessions that have lived past SESSION_TTL_MS. Keeps the map bounded
     * even if the user closes the browser mid-flow without ever polling the
     * terminal state.
     */
    private void evictExpiredSessions() {
        long cutoff = System.currentTimeMillis() - SESSION_TTL_MS;
        Iterator<Map.Entry<String, RegistrationSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().createdAtMs < cutoff) it.remove();
        }
    }

    public enum Status {
        /** Worker started but onQRCode hasn't fired yet — QR not ready */
        PENDING,
        /** QR available, polling for user scan + confirmation */
        WAITING,
        /** User confirmed, clientId/clientSecret returned */
        CONFIRMED,
        /** QR expired before user finished scanning */
        EXPIRED,
        /** User denied authorization on the feishu side */
        DENIED,
        /** Network or SDK error */
        ERROR
    }

    /**
     * 一次注册流程的可观察状态。线程安全：所有写操作都在 SDK 工作线程，读操作在 HTTP 轮询线程，
     * 字段全部 volatile 即可。
     */
    public static class RegistrationSession {
        public final String sessionId;
        final long createdAtMs = System.currentTimeMillis();

        public volatile Status status = Status.PENDING;
        public volatile String qrcodeUrl;
        public volatile int qrcodeExpireSeconds;
        /** Cached "data:image/png;base64,..." rendered from qrcodeUrl by the controller. */
        public volatile String qrcodeImgDataUri;
        public volatile String clientId;
        public volatile String clientSecret;
        public volatile String userOpenId;
        public volatile String userTenantBrand;
        public volatile String errorMessage;
        public volatile long lastUpdateMs = System.currentTimeMillis();

        RegistrationSession(String sessionId) {
            this.sessionId = sessionId;
        }

        void onQRCode(QRCodeInfo qr) {
            this.qrcodeUrl = qr.getUrl();
            this.qrcodeExpireSeconds = qr.getExpireIn();
            this.status = Status.WAITING;
            this.lastUpdateMs = System.currentTimeMillis();
            log.info("[feishu-register] QR ready, expires in {}s", qr.getExpireIn());
        }

        void onStatusChange(StatusChangeInfo info) {
            // SDK 状态码：POLLING / SLOW_DOWN / DOMAIN_SWITCHED。三种都是"还在等"，没有更细的
            // "已扫码未确认"信号 —— 飞书侧扫码确认在同一步完成。我们只用 lastUpdateMs 让
            // 前端知道连接还活着，状态本身保持 WAITING 直到终态。
            this.lastUpdateMs = System.currentTimeMillis();
        }

        void onSuccess(RegisterAppResult result) {
            this.clientId = result.getClientId();
            this.clientSecret = result.getClientSecret();
            if (result.getUserInfo() != null) {
                this.userOpenId = result.getUserInfo().getOpenId();
                this.userTenantBrand = result.getUserInfo().getTenantBrand();
            }
            this.status = Status.CONFIRMED;
            this.lastUpdateMs = System.currentTimeMillis();
            log.info("[feishu-register] confirmed, clientId={}", clientId);
        }

        void onTerminal(Status terminalStatus, String message) {
            this.status = terminalStatus;
            this.errorMessage = message;
            this.lastUpdateMs = System.currentTimeMillis();
        }
    }
}
