package br.com.fixflow.modules.tenants.domain;
import java.time.Instant;
public record QuoteDefaults(Instant validUntil, String warrantyTerms, String logoDataUrl) { }
