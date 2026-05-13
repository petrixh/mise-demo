package com.example.mise.domain.household;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA {@link AttributeConverter} that serializes {@code List<String>} to/from
 * a JSON array string stored in a VARCHAR/CLOB column.
 * Uses Jackson 3 ({@code tools.jackson}).
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            var type = MAPPER.getTypeFactory().constructCollectionType(List.class, String.class);
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
