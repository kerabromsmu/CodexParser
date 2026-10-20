package se.dimage.codexparser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Searches snapshots of chat messages without retaining the transcripts in memory. */
public final class ConversationSearch {
    private final RolloutReader reader = new RolloutReader();

    public record SearchResult(Set<Path> matches, List<String> warnings) {
        public SearchResult {
            matches = Set.copyOf(matches);
            warnings = List.copyOf(warnings);
        }
    }

    public SearchResult search(List<Conversation> conversations, String query, BooleanSupplier cancelled) {
        String normalized = normalizeQuery(query);
        var matches = new HashSet<Path>();
        var warnings = new ArrayList<String>();
        for (Conversation conversation : conversations) {
            RolloutReader.checkCancelled(cancelled);
            if (matchesMetadata(conversation, normalized)) {
                matches.add(conversation.source());
                continue;
            }
            try {
                // Track priorities and matches per speaker, as conversation-mode exports do.
                // This prevents a discarded duplicate representation from producing a false hit.
                Map<String, Integer> priorities = new HashMap<>();
                Map<String, Integer> matchingPriorities = new HashMap<>();
                long[] malformed = {0};
                reader.read(conversation.source(), Files.size(conversation.source()), cancelled, record -> {
                    if (record.error() != null) {
                        malformed[0]++;
                        return;
                    }
                    var message = LogContent.message(record.data());
                    if (!LogContent.isConversation(message)) return;
                    priorities.merge(message.role(), message.priority(), Math::min);
                    if (message.text().toLowerCase(Locale.ROOT).contains(normalized)) {
                        matchingPriorities.merge(message.role(), message.priority(), Math::min);
                    }
                });
                if (matchingPriorities.entrySet().stream()
                        .anyMatch(entry -> entry.getValue().equals(priorities.get(entry.getKey())))) {
                    matches.add(conversation.source());
                }
                if (malformed[0] > 0) {
                    warn(warnings, conversation.source() + ": " + malformed[0] + " malformed log line(s) skipped");
                }
            } catch (IOException e) {
                warn(warnings, conversation.source() + ": " + e.getMessage());
            }
        }
        RolloutReader.checkCancelled(cancelled);
        return new SearchResult(matches, warnings);
    }

    static String normalizeQuery(String query) {
        return query.strip().toLowerCase(Locale.ROOT);
    }

    static boolean matchesMetadata(Conversation conversation, String normalizedQuery) {
        return (conversation.title() + " " + conversation.project() + " " + conversation.id() + " " + conversation.source())
                .toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < 100) warnings.add("Content search: " + message);
        else if (warnings.size() == 100) warnings.add("Further content-search warnings omitted.");
    }
}
