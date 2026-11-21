package lab;

import java.lang.reflect.*;
import java.util.*;

/** Output only. Input fixtures use the deliberately small CSV format. */
public final class Json {
    private Json() {}
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Enum<?>) return quote(value.toString());
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(entry -> quote(entry.getKey().toString()) + ":" + encode(entry.getValue())).toList()) + "}";
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(Json::encode).toList()) + "]";
        if (value.getClass().isRecord()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try { fields.put(component.getName(), component.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Cannot encode record", error); }
            }
            return encode(fields);
        }
        throw new IllegalArgumentException("Unsupported JSON output type");
    }
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) switch (c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
        }
        return out.append('"').toString();
    }
}
