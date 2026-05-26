package com.mnemoscape.memory.model.converter;

import com.mnemoscape.memory.model.entity.MemoryVersion;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter(autoApply = false)
public class MemoryVersionChangeTypeConverter implements AttributeConverter<MemoryVersion.ChangeType, String> {

    @Override
    public String convertToDatabaseColumn(MemoryVersion.ChangeType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public MemoryVersion.ChangeType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return switch (dbData.trim().toLowerCase(Locale.ROOT)) {
            case "create" -> MemoryVersion.ChangeType.CREATE;
            case "modify" -> MemoryVersion.ChangeType.MODIFY;
            case "drift" -> MemoryVersion.ChangeType.DRIFT;
            case "lock" -> MemoryVersion.ChangeType.LOCK;
            case "restore" -> MemoryVersion.ChangeType.RESTORE;
            default -> null;
        };
    }
}
