package br.com.fixflow.modules.quotes.application;

import java.time.Instant;
import java.util.UUID;

/** Called by the OS workflow within its authorized transaction and work-order lock. */
public interface QuoteWorkflow {
    String sent(UUID tenant, UUID order, UUID quote, Instant at);
    void revoke(UUID tenant, UUID order);
    void decided(UUID tenant, UUID quote, String status);
}
