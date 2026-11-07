package br.com.fixflow.modules.quotes.application;

import java.time.Instant;
import java.util.UUID;

/** Called by the OS workflow within its authorized transaction and work-order lock. */
public interface QuoteWorkflow {
    String sent(UUID tenant, UUID order, UUID quote, Instant at);
    void revoke(UUID tenant, UUID order);
    record ManualApproval(UUID id, UUID quoteId, int quoteVersion, String contentHash, String actorName) { }
    ManualApproval approveManually(UUID tenant, UUID order, UUID quote, UUID actor, String channel, String reason, String ip, String userAgent, Instant at);
    void decided(UUID tenant, UUID quote, String status, Instant at);
}
