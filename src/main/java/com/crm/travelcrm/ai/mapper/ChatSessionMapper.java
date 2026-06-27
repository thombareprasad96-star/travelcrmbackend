package com.crm.travelcrm.ai.mapper;

import com.crm.travelcrm.ai.dto.ChatSessionDto;
import com.crm.travelcrm.ai.entity.ChatSession;
import org.springframework.stereotype.Component;

/** Hand-written mapper (no MapStruct), publicId only. */
@Component
public class ChatSessionMapper {

    public ChatSessionDto toDto(ChatSession s) {
        if (s == null) return null;
        return ChatSessionDto.builder()
                .publicId(s.getPublicId())
                .title(s.getTitle())
                .lastMessageAt(s.getLastMessageAt())
                .createdAt(s.getCreatedAt())
                .build();
    }
}