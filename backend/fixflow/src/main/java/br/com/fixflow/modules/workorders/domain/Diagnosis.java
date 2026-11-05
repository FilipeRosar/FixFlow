package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.util.UUID;

public record Diagnosis(UUID id, int revision, String findings, String probableCause, String proposedSolution,
                        UUID recordedBy, String recordedByName, Instant createdAt) { }
