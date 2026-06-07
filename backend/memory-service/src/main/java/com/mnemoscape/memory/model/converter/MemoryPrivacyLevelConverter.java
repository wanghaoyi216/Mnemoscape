package com.mnemoscape.memory.model.converter;

import com.mnemoscape.memory.model.entity.Memory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter(autoApply = false)
public class MemoryPrivacyLevelConverter implements AttributeConverter<Memory.PrivacyLevel, String> {

    @Override
    public String convertToDatabaseColumn(Memory.PrivacyLevel attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Memory.PrivacyLevel convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Memory.PrivacyLevel.PRIVATE;
        }
        return switch (dbData.trim().toLowerCase(Locale.ROOT)) {
            case "private" -> Memory.PrivacyLevel.PRIVATE;
            case "friends" -> Memory.PrivacyLevel.FRIENDS;
            case "public" -> Memory.PrivacyLevel.PUBLIC;
            default -> Memory.PrivacyLevel.PRIVATE;
        };
    }
}
