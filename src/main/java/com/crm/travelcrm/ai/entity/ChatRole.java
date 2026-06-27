package com.crm.travelcrm.ai.entity;

/** Role of a persisted chat turn. SYSTEM/TOOL are stored for audit/visibility but not replayed as history. */
public enum ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}