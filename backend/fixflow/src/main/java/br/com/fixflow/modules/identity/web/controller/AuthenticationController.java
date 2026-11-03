package br.com.fixflow.modules.identity.web.controller;

import br.com.fixflow.modules.identity.application.dto.SessionTokens;
import br.com.fixflow.modules.identity.application.exception.SessionRejectedException;
import br.com.fixflow.modules.identity.application.usecase.LoginUseCase;
import br.com.fixflow.modules.identity.application.usecase.RefreshSessionUseCase;
import br.com.fixflow.modules.identity.web.dto.LoginRequest;
import br.com.fixflow.modules.identity.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final LoginUseCase login;
    private final RefreshSessionUseCase refresh;
    private final boolean secure;
    private final Clock clock;
    public AuthenticationController(LoginUseCase login, RefreshSessionUseCase refresh, Clock clock,
                                    @Value("${fixflow.auth.cookie-secure:true}") boolean secure) {
        this.login = login;
        this.refresh = refresh;
        this.clock = clock;
        this.secure = secure;
    }
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return response(login.login(request.email(), request.password(), request.tenantId()));
    }
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@CookieValue(name = "fixflow_refresh", required = false) String token) {
        return response(refresh.refresh(token));
    }
    private ResponseEntity<LoginResponse> response(SessionTokens tokens) {
        var identity = tokens.identity();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), Duration.between(clock.instant(), tokens.refreshExpiresAt())).toString())
                .body(new LoginResponse(tokens.accessToken(), "Bearer", tokens.expiresIn(), identity.userId(), identity.tenantId(), identity.role()));
    }
    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from("fixflow_refresh", value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api/v1/auth").maxAge(maxAge).build();
    }
    @ExceptionHandler(SessionRejectedException.class)
    public ResponseEntity<ProblemDetail> rejected() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                        "Não foi possível autenticar. Verifique as credenciais ou tente novamente mais tarde."));
    }
}
