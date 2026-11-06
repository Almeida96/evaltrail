package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import static java.nio.charset.StandardCharsets.UTF_8;

final class Json {
    static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48).maxStringLength(65_536).maxNumberLength(128).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .enable(SerializationFeature.INDENT_OUTPUT).build();

    private Json() {}

    static JsonNode parse(byte[] bytes) throws IOException {
        JsonNode node = MAPPER.readTree(bytes);
        require(node != null && !node.isMissingNode(), "JSON document is empty");
        validateUnicode(node);
        return node;
    }

    static JsonNode read(Path path, int maxBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            require(bytes.length <= maxBytes, "JSON file exceeds the size limit");
            return parse(bytes);
        }
    }

    static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        require(node != null && node.isObject(), "Expected a JSON object");
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key), "Unexpected JSON field"));
        required.forEach(key -> require(node.has(key), "Required JSON field is missing"));
    }

    static String text(JsonNode node, int max) {
        require(node != null && node.isTextual() && !node.textValue().isEmpty() && node.textValue().length() <= max, "Invalid string field");
        return node.textValue();
    }

    static String id(JsonNode node) {
        String value = text(node, 80);
        require(value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "Invalid identifier");
        return value;
    }

    static long integer(JsonNode node, long min, long max) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong(), "Expected an integer");
        long value = node.longValue();
        require(value >= min && value <= max, "Integer outside allowed range");
        return value;
    }

    static String canonical(JsonNode node) {
        if (node.isObject()) {
            var keys = new ArrayList<String>(); node.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            return "{" + String.join(",", keys.stream().map(key -> quote(key) + ":" + canonical(node.get(key))).toList()) + "}";
        }
        if (node.isArray()) {
            var values = new ArrayList<String>(); node.forEach(value -> values.add(canonical(value)));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toString();
        return node.toString();
    }

    private static String quote(String value) {
        return MAPPER.getNodeFactory().textNode(value).toString();
    }

    static String digest(JsonNode node) {
        // UTF-8 replaces lone surrogates with '?', which would collapse distinct case inputs.
        // Check programmatically constructed trees as well as parsed JSON.
        validateUnicode(node);
        return hash(canonical(node));
    }
    private static void validateUnicode(JsonNode node) {
        if (node.isTextual()) validateUnicodeText(node.textValue());
        if (node.isObject()) node.fieldNames().forEachRemaining(Json::validateUnicodeText);
        if (node.isContainerNode()) node.forEach(Json::validateUnicode);
    }
    private static void validateUnicodeText(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                require(++index < text.length() && Character.isLowSurrogate(text.charAt(index)), "Unpaired Unicode surrogate");
            } else require(!Character.isLowSurrogate(value), "Unpaired Unicode surrogate");
        }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    static final class InputException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        InputException(String message) { super(message); }
    }
    static void require(boolean condition, String message) { if (!condition) throw new InputException(message); }
}
