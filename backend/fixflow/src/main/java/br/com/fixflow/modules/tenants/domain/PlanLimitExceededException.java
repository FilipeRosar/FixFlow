package br.com.fixflow.modules.tenants.domain;

public class PlanLimitExceededException extends RuntimeException {
    private final String resource;
    public PlanLimitExceededException(String resource) {
        super("Limite de " + resource + " do plano atingido. Faça upgrade do plano para continuar criando.");
        this.resource = resource;
    }
    public String resource() { return resource; }
}
