package se.dimage.codexparser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.regex.Pattern;

final class LogContent {
    private static final Pattern SCAFFOLD = Pattern.compile(
            "(?s)^\\s*(?:# AGENTS\\.md instructions[^\\n]*\\R?|<(?:environment_context|permissions instructions|skills_instructions|user_instructions)>)");

    private LogContent() {
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) return new JsonObject();
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject parent, String key) {
        if (parent == null) return "";
        JsonElement value = parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static String value(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) return "";
        JsonElement value = parent.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    static String text(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            var result = new StringBuilder();
            for (JsonElement block : content.getAsJsonArray()) {
                String part = text(block);
                if (!part.isEmpty()) {
                    if (!result.isEmpty()) result.append("\n\n");
                    result.append(part);
                }
            }
            return result.toString();
        }
        JsonObject block = content.getAsJsonObject();
        String type = string(block, "type");
        if (block.has("text") && block.get("text").isJsonPrimitive()) return string(block, "text");
        if (type.toLowerCase(java.util.Locale.ROOT).contains("image")) return "[Image attachment]";
        return "[Attachment: " + (type.isBlank() ? "unknown" : type) + "]";
    }

    static Message message(JsonObject data) {
        if (data == null) return null;
        JsonObject payload = object(data, "payload");
        String recordType = string(data, "type");
        String type = string(payload, "type");
        if (recordType.equals("response_item") && type.equals("message")) {
            return new Message(string(payload, "role"), text(payload.get("content")),
                    string(payload, "id"), 0, string(payload, "phase"));
        }
        if (recordType.equals("event_msg") && type.equals("item_completed")) {
            JsonObject item = object(payload, "item");
            String itemType = string(item, "type");
            if (List.of("UserMessage", "AgentMessage").contains(itemType)) {
                return new Message(itemType.equals("UserMessage") ? "user" : "assistant",
                        text(item.get("content")), string(item, "id"), 1, string(item, "phase"));
            }
        }
        if (recordType.equals("event_msg") && List.of("user_message", "agent_message").contains(type)) {
            return new Message(type.equals("user_message") ? "user" : "assistant",
                    string(payload, "message"), "", 2, "");
        }
        return null;
    }

    static boolean isConversation(Message message) {
        return message != null && List.of("user", "assistant").contains(message.role())
                && !message.text().isBlank()
                && !(message.role().equals("user") && isScaffolding(message.text()));
    }

    static boolean isScaffolding(String text) {
        String remaining = text.strip();
        // Remove only complete leading instruction envelopes, retaining any actual prompt after them.
        for (String tag : List.of("environment_context", "user_instructions", "skills_instructions")) {
            while (remaining.startsWith("<" + tag + ">")) {
                int end = remaining.indexOf("</" + tag + ">");
                if (end < 0) break;
                remaining = remaining.substring(end + tag.length() + 3).strip();
            }
        }
        return remaining.isBlank() || (remaining.equals(text.strip()) && SCAFFOLD.matcher(text).find());
    }

    record Message(String role, String text, String id, int priority, String phase) {
    }
}
