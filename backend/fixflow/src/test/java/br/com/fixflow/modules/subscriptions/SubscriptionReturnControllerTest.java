package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.web.SubscriptionReturnController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SubscriptionReturnControllerTest {
    @Test void redirectsOnlyToConfiguredFrontendWithoutTrustingPaymentParameters() {
        var response=new SubscriptionReturnController("http://localhost:4200").back();
        assertEquals(303,response.getStatusCode().value());
        assertEquals("http://localhost:4200/inicio/configuracoes/assinatura?assinatura=retorno",response.getHeaders().getLocation().toString());
    }
    @Test void rejectsUntrustedOrigins() {
        assertThrows(IllegalArgumentException.class,()->new SubscriptionReturnController("http://external.invalid"));
        assertThrows(IllegalArgumentException.class,()->new SubscriptionReturnController("https://user@external.invalid"));
    }
}
