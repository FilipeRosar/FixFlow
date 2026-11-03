package br.com.fixflow.modules.identity.web.controller;
import br.com.fixflow.modules.identity.application.usecase.AccountRecovery;
import br.com.fixflow.modules.identity.application.exception.ResetRejectedException;
import br.com.fixflow.modules.identity.web.dto.*;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/auth")
public class AccountRecoveryController {
    private static final Logger LOG = LoggerFactory.getLogger(AccountRecoveryController.class);
    private final AccountRecovery recovery;
    private final TaskExecutor executor;
    private final boolean secure;
    public AccountRecoveryController(AccountRecovery recovery, @Qualifier("passwordRecoveryExecutor") TaskExecutor executor,
                                     @Value("${fixflow.auth.cookie-secure:true}") boolean secure) {
        this.recovery = recovery; this.executor = executor; this.secure = secure;
    }
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        // Queue both known and unknown accounts: neither lookup nor SMTP affects the response.
        try {
            executor.execute(() -> {
                try { recovery.requestReset(request.email()); }
                catch (RuntimeException exception) { LOG.warn("Password recovery processing failed; check database and SMTP availability."); }
            });
        } catch (TaskRejectedException exception) { LOG.warn("Password recovery queue is full."); }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Se houver uma conta para este e-mail, você receberá um link para redefinir sua senha."));
    }
    @PostMapping("/reset-password")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        recovery.resetPassword(request.token(), request.newPassword());
        return cleared();
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "fixflow_refresh", required = false) String token) {
        recovery.logout(token);
        return cleared();
    }
    private ResponseEntity<Void> cleared() {
        var cookie = ResponseCookie.from("fixflow_refresh", "").httpOnly(true).secure(secure).sameSite("Strict")
                .path("/api/v1/auth").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
    @ExceptionHandler(ResetRejectedException.class)
    public ResponseEntity<ProblemDetail> rejected() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Link inválido ou expirado. Solicite um novo link de recuperação."));
    }
}
