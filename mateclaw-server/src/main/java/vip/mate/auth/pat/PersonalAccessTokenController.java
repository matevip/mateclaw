package vip.mate.auth.pat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * RFC-03 Lane I1 — Personal Access Token CRUD endpoints.
 *
 * <p>Authenticated callers (JWT or another PAT) manage their own tokens
 * here. Cross-user access is impossible: every query is scoped to
 * {@code Authentication.getName()} server-side, so tampering with the
 * {@code X-User-Id} header has no effect.
 *
 * <p>Plaintext is returned exactly once on {@link #create}; subsequent
 * lookups expose only metadata (id, name, scopes, last_used_at,
 * expires_at). The DB never stores plaintext at any point.
 */
@Tag(name = "Personal Access Tokens")
@RestController
@RequestMapping("/api/v1/auth/tokens")
@RequiredArgsConstructor
public class PersonalAccessTokenController {

    private final PersonalAccessTokenService patService;
    private final AuthService authService;

    @Operation(summary = "List my PATs (metadata only — plaintext is never returned after creation)")
    @GetMapping
    public R<List<PersonalAccessTokenEntity>> list(Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(patService.listByUser(user.getId()));
    }

    @Operation(summary = "Mint a new PAT — returned plaintext is shown once and cannot be recovered")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody CreateRequest req, Authentication auth) {
        UserEntity user = requireUser(auth);
        PersonalAccessTokenService.CreatedToken created = patService.create(
                user.getId(),
                req.name(),
                req.scopes(),
                req.expiresAt());
        // Return plaintext + metadata; UI must surface plaintext immediately
        // and warn the user it won't be shown again.
        return R.ok(Map.of(
                "id", created.id(),
                "plaintext", created.plaintext(),
                "name", req.name() == null ? "" : req.name(),
                "scopes", req.scopes() == null ? "" : req.scopes(),
                "expiresAt", req.expiresAt() == null ? "" : req.expiresAt()));
    }

    @Operation(summary = "Revoke a PAT — soft-delete; further auth attempts with this token will fail")
    @DeleteMapping("/{id}")
    public R<Void> revoke(@PathVariable Long id, Authentication auth) {
        UserEntity user = requireUser(auth);
        patService.revoke(id, user.getId());
        return R.ok();
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null) {
            throw new MateClawException("err.auth.user_not_found",
                    "Authenticated user not found: " + auth.getName());
        }
        return user;
    }

    /** Inbound DTO for {@link #create}. {@code name} and {@code scopes} are
     *  optional; {@code expiresAt} null means the token never expires until
     *  manually revoked. */
    public record CreateRequest(String name, String scopes, LocalDateTime expiresAt) {}
}
