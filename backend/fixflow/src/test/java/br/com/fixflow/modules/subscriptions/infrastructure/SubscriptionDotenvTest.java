package br.com.fixflow.modules.subscriptions.infrastructure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SubscriptionDotenvTest {
    @Test void loadsAsaasCredentialsLiterally() {
        var values=SubscriptionDotenv.parse("ASAAS_ENABLED=true\nASAAS_BASE_URL=https://api-sandbox.asaas.com/v3\nASAAS_API_KEY='$aact_literal$key'\nASAAS_WEBHOOK_TOKEN=separate-secret\nDB_PASSWORD=private\n");
        assertEquals("true",values.get("ASAAS_ENABLED"));
        assertEquals("https://api-sandbox.asaas.com/v3",values.get("ASAAS_BASE_URL"));
        assertEquals("$aact_literal$key",values.get("ASAAS_API_KEY"));
        assertEquals("separate-secret",values.get("ASAAS_WEBHOOK_TOKEN"));
        assertFalse(values.containsKey("DB_PASSWORD"));
    }
    @Test void loadsPaymentValuesWithoutExpandingSecretsOrOverridingDatabaseConfiguration() {
        var values=SubscriptionDotenv.parse("# Example\nexport SUBSCRIPTIONS_ENABLED=true\nMERCADOPAGO_ACCESS_TOKEN='secret$literal'\nMERCADOPAGO_WEBHOOK_SECRET=other # comment\nDB_PASSWORD=private\n");
        assertEquals("true",values.get("SUBSCRIPTIONS_ENABLED"));
        assertEquals("secret$literal",values.get("MERCADOPAGO_ACCESS_TOKEN"));
        assertEquals("other",values.get("MERCADOPAGO_WEBHOOK_SECRET"));
        assertFalse(values.containsKey("DB_PASSWORD"));
    }
}
