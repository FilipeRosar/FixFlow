package br.com.fixflow.modules.subscriptions.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;

/** The return is navigation only. Payment access is granted solely by server reconciliation. */
@RestController
public class SubscriptionReturnController {
    private final URI target;
    public SubscriptionReturnController(@Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String origin) {
        URI configured=URI.create(origin);
        if(configured.getHost()==null || configured.getUserInfo()!=null || configured.getQuery()!=null || configured.getFragment()!=null
            || !("https".equals(configured.getScheme()) || ("http".equals(configured.getScheme()) &&
                java.util.Set.of("localhost","127.0.0.1","[::1]").contains(configured.getHost()))))
            throw new IllegalArgumentException("Origem do frontend inválida.");
        target=URI.create(origin.replaceAll("/+$","")+"/inicio/configuracoes/assinatura?assinatura=retorno");
    }
    @GetMapping("/public/subscriptions/return") public ResponseEntity<Void> back() {
        return ResponseEntity.status(303).location(target).header("Cache-Control","no-store").build();
    }
}
