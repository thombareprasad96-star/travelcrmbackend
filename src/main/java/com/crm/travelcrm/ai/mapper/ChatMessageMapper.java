package com.crm.travelcrm.ai.mapper;

import com.crm.travelcrm.ai.dto.ChatMessageDto;
import com.crm.travelcrm.ai.entity.ChatMessage;
import org.springframework.stereotype.Component;

/** Hand-written mapper (no MapStruct), publicId only. */
@Component
public class ChatMessageMapper {

    public ChatMessageDto toDto(ChatMessage m) {
        if (m == null) return null;
        return ChatMessageDto.builder()
                .publicId(m.getPublicId())
                .role(m.getRole() != null ? m.getRole().name() : null)
                .content(m.getContent())
                .toolName(m.getToolName())
                .createdAt(m.getCreatedAt())
                .build();
    }
}