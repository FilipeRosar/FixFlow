package br.com.fixflow.modules.subscriptions.infrastructure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local payment configuration only; never replaces deployment environment variables or database settings. */
public class SubscriptionDotenv implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("integration-test","test"))) return;
        for(Path file:java.util.List.of(Path.of(".env"),Path.of("../../.env"))) {
            if(Files.isRegularFile(file)) {
                try { environment.getPropertySources().addLast(new MapPropertySource("localSubscriptionDotenv",parse(Files.readString(file)))); }
                catch(IOException failure) { throw new UncheckedIOException("Não foi possível ler o .env local.",failure); }
                break;
            }
        }
    }
    static Map<String,Object> parse(String contents) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String line:contents.split("\\R")) {
            line=line.strip();
            if(line.startsWith("export ")) line=line.substring(7).strip();
            int equals=line.indexOf('=');
            if(line.startsWith("#") || equals<1) continue;
            String key=line.substring(0,equals).strip();
            if(!(key.startsWith("MERCADOPAGO_") || key.startsWith("SUBSCRIPTIONS_") || key.startsWith("SUBSCRIPTION_") || key.startsWith("ASAAS_"))) continue;
            String value=line.substring(equals+1).strip();
            if(value.length()>=2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                value=value.substring(1,value.length()-1);
            else {
                int comment=value.indexOf(" #");
                if(comment>=0) value=value.substring(0,comment).stripTrailing();
            }
            result.put(key,value);
        }
        return result;
    }
}
