package br.com.fixflow.modules.quotes.infrastructure;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class PublicQuoteGuardFilter extends OncePerRequestFilter {
    private final PublicQuoteRateLimiter limiter;
    private final String allowedOrigin;
    private static final String PREFIX = "/public/v1/work-orders";
    public PublicQuoteGuardFilter(PublicQuoteRateLimiter limiter, @org.springframework.beans.factory.annotation.Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String allowedOrigin) { this.limiter = limiter; this.allowedOrigin = allowedOrigin; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(PREFIX) && !path.startsWith(PREFIX + "/");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String token = path.startsWith(PREFIX + "/") ? path.substring(PREFIX.length() + 1) : "";
        var matcher = java.util.regex.Pattern.compile("([A-Za-z0-9_-]{43})(?:/(approve|reject))?").matcher(token);
        boolean validFormat = matcher.matches();
        boolean decisionPath = validFormat && matcher.group(2) != null;
        if (validFormat) token = matcher.group(1);
        String tokenHash = validFormat ? hash(token) : null;
        var admission = limiter.acquire(hash(String.valueOf(request.getRemoteAddr())), tokenHash);
        if (!admission.allowed()) {
            response.setStatus(429); response.setHeader("Retry-After", String.valueOf(admission.retryAfter()));
            response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":429,\"detail\":\"Muitas consultas. Aguarde antes de tentar novamente.\"}"); return;
        }
        boolean allowedMethod = decisionPath ? request.getMethod().equals("POST") : request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        if (!validFormat || !allowedMethod) {
            response.setStatus(404); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":404,\"detail\":\"Orçamento não encontrado ou indisponível.\"}"); return;
        }
        if (decisionPath) {
            String origin = request.getHeader("Origin");
            if (origin != null && !allowedOrigin.equals(origin) || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                response.setStatus(403); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":403,\"detail\":\"Origem da solicitação não autorizada.\"}"); return;
            }
            String type = request.getContentType();
            if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
                response.setStatus(415); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":415,\"detail\":\"Envie a decisão em JSON.\"}"); return;
            }
        }
        request.setAttribute("publicQuoteHash", tokenHash);
        chain.doFilter(request, response);
    }
}
