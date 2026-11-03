package br.com.fixflow.modules.identity.application.port;

public interface RefreshSecrets {
    String generate();
    String hash(String value);
}
