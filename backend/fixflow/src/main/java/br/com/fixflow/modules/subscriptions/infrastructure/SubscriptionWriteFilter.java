package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires first payment for paid signups and current access for business mutations. */
public class SubscriptionWriteFilter extends OncePerRequestFilter {
    private final Subscriptions subscriptions;
    public SubscriptionWriteFilter(Subscriptions subscriptions) { this.subscriptions=subscriptions; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return request.getMethod().equals("OPTIONS") || !path.startsWith("/api/")
            || (path.equals("/api/v1/tenant") && request.getMethod().equals("DELETE"))
            || path.startsWith("/api/contas") || path.startsWith("/api/v1/auth/")
            || path.equals("/api/v1/subscription") || path.startsWith("/api/v1/subscription/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.getPrincipal() instanceof AuthenticatedIdentity identity&&identity.tenantId()!=null) {
            var access = subscriptions.access();
            boolean activation = access.status().equals("PAYMENT_REQUIRED");
            boolean read = Set.of("GET", "HEAD").contains(request.getMethod());
            if (activation || (!read && !access.writable())) {
                response.setStatus(402); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
                if (activation) {
                    response.getWriter().write("{\"status\":402,\"code\":\"PAYMENT_REQUIRED\",\"detail\":\"Conclua o pagamento da assinatura para ativar sua conta.\"}");
                    return;
                }
                response.getWriter().write("{\"status\":402,\"code\":\"SUBSCRIPTION_REQUIRED\",\"detail\":\"Assinatura expirada. Seus dados continuam disponíveis para leitura. O proprietário pode regularizar em Configurações → Assinatura.\"}");
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
