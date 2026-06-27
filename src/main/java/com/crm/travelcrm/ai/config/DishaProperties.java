package com.crm.travelcrm.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Disha tunables (prefix {@code disha}). RAG is off until pgvector is installed (Step B). */
@Component
@ConfigurationProperties(prefix = "disha")
@Getter
@Setter
public class DishaProperties {

    /** Max prior turns replayed as conversation memory (caps prompt size). */
    private int contextMaxTurns = 10;

    /** Knowledge-base retrieval (Step B). Requires the pgvector extension + ingested chunks. */
    private boolean ragEnabled = false;
}