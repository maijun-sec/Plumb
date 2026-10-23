package org.plumb.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One tool's OpenAI function-calling schema. The Re-Act agent converts every registered
 * {@link Tool} into a {@code ToolDefinition} and ships the full list in the {@code tools} field of
 * the chat-completion request.
 *
 * <p>Schema fields:
 *
 * <ul>
 *   <li>{@code type} ? always {@code "function"}
 *   <li>{@code function.name} ? the tool's stable id; the model echoes this in {@code
 *       tool_calls[].function.name}.
 *   <li>{@code function.description} ? human prose; the model uses this to decide when to call the
 *       tool.
 *   <li>{@code function.parameters} ? JSON Schema object; {@code type: "object"}, {@code
 *       properties: {...}}, {@code required: [...]}.
 * </ul>
 *
 * <p>Construction is plain {@link ObjectNode} JSON ? no Spring-AI, no BeanInfo magic. The {@link
 * #toJsonNode(ObjectMapper)} method converts to the wire shape that {@code AIProvider} serialises
 * into the request body.
 */
public final class ToolDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String description;
    private final Map<String, Object> properties;
    private final List<String> required;

    public ToolDefinition(
            String name, String description, Map<String, Object> properties, List<String> required) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
        this.required = required == null ? List.of() : List.copyOf(required);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public List<String> required() {
        return required;
    }

    /**
     * Convert to OpenAI's wire format: {@code
     * {"type":"function","function":{"name":...,"description":...,"parameters":{...}}}}.
     */
    public ObjectNode toJsonNode(ObjectMapper mapper) {
        ObjectMapper m = mapper == null ? MAPPER : mapper;
        ObjectNode root = m.createObjectNode();
        root.put("type", "function");
        ObjectNode fn = root.putObject("function");
        fn.put("name", name);
        fn.put("description", description);
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        for (var e : properties.entrySet()) {
            String pname = e.getKey();
            ObjectNode prop = props.putObject(pname);
            if (e.getValue() instanceof Map<?, ?> nested) {
                for (var ne : nested.entrySet()) {
                    String nk = String.valueOf(ne.getKey());
                    if (ne.getValue() instanceof String s) {
                        prop.put(nk, s);
                    } else if (ne.getValue() instanceof Number n) {
                        prop.put(nk, n.doubleValue());
                    } else if (ne.getValue() instanceof Boolean b) {
                        prop.put(nk, b);
                    } else {
                        prop.putPOJO(nk, ne.getValue());
                    }
                }
            } else if (e.getValue() instanceof String s) {
                prop.put("type", s);
            } else if (e.getValue() instanceof Number n) {
                prop.put("type", n.doubleValue());
            } else {
                prop.putPOJO("type", e.getValue());
            }
        }
        if (!required.isEmpty()) {
            ArrayNode req = params.putArray("required");
            for (String r : required) req.add(r);
        }
        return root;
    }
}
