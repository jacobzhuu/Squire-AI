package dev.squire.server.input;

import java.util.*;

/** Resolves names across the whole roster before interpreting an order. */
public final class AgentAddressing {
    public record Candidate(UUID id, String name) {}
    public record Result(UUID target, String text, boolean ambiguous) {}
    private record Hit(Candidate candidate, int start, int end) {}
    private AgentAddressing() {}

    public static Result resolve(String raw, List<Candidate> roster) {
        String text = raw == null ? "" : raw.trim();
        String lower = text;
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : roster) {
            String name = candidate.name() == null ? "" : candidate.name().trim();
            if (name.isEmpty()) continue;
            for (int at=0;at<=text.length()-name.length();at++) {
                if(!text.regionMatches(true,at,name,0,name.length()))continue;
                int end = at + name.length();
                if (at > 0 && asciiWord(name.charAt(0)) && asciiWord(lower.charAt(at - 1))) continue;
                if (end < lower.length() && asciiWord(name.charAt(name.length()-1)) && asciiWord(lower.charAt(end))) continue;
                hits.add(new Hit(candidate, at, end));
            }
        }
        List<Hit> longest = hits.stream().filter(h -> hits.stream().noneMatch(other ->
            other.start <= h.start && other.end >= h.end && other.end-other.start > h.end-h.start)).toList();
        Set<UUID> targets = new LinkedHashSet<>();
        longest.forEach(h -> targets.add(h.candidate.id()));
        if (targets.size() > 1) return new Result(null, text, true);
        if (targets.size() == 1) {
            Hit hit = longest.get(0);
            return new Result(hit.candidate.id(), Addressing.join(text.substring(0,hit.start),text.substring(hit.end)), false);
        }
        var generic = Addressing.parse(text, null);
        if (generic.addressed()) {
            if (roster.size() == 1) return new Result(roster.get(0).id(), generic.text(), false);
            return new Result(null, text, roster.size() > 1);
        }
        return new Result(null, text, false);
    }

    private static boolean asciiWord(char c) {
        c=Character.toLowerCase(c);
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }
}
