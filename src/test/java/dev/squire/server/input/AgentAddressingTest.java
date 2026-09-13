package dev.squire.server.input;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentAddressingTest {
    private final UUID oo=UUID.randomUUID(), ii=UUID.randomUUID();
    private List<AgentAddressing.Candidate> pair() {
        return List.of(new AgentAddressing.Candidate(oo,"oo"),new AgentAddressing.Candidate(ii,"ii"));
    }
    @Test void resolvesExactNameRegardlessOfRosterOrder() {
        var r=AgentAddressing.resolve("@OO，给我面包",pair());
        assertEquals(oo,r.target());assertEquals("给我面包",r.text());assertFalse(r.ambiguous());
        assertEquals(ii,AgentAddressing.resolve("ii给我面包",pair()).target());
    }
    @Test void refusesGenericAndMultipleNames() {
        assertTrue(AgentAddressing.resolve("侍从跟我来",pair()).ambiguous());
        assertTrue(AgentAddressing.resolve("oo和ii跟我来",pair()).ambiguous());
        assertTrue(AgentAddressing.resolve("oo follow ii",pair()).ambiguous());
    }
    @Test void doesNotMatchInsideEnglishWord() {
        assertNull(AgentAddressing.resolve("food is good",pair()).target());
        assertNull(AgentAddressing.resolve("book",pair()).target());
    }
    @Test void prefersLongerChineseNameButDetectsSeparateCalls() {
        var names=List.of(new AgentAddressing.Candidate(oo,"豆"),new AgentAddressing.Candidate(ii,"豆包"));
        assertEquals(ii,AgentAddressing.resolve("豆包给我东西",names).target());
        assertTrue(AgentAddressing.resolve("豆和豆包过来",names).ambiguous());
    }
    @Test void duplicateNamesRequireUuid() {
        var names=List.of(new AgentAddressing.Candidate(oo,"oo"),new AgentAddressing.Candidate(ii,"OO"));
        assertTrue(AgentAddressing.resolve("oo给我东西",names).ambiguous());
    }
    @Test void singleCompanionAllowsGenericName() {
        assertEquals(oo,AgentAddressing.resolve("侍从，跟着我",pair().subList(0,1)).target());
    }
    @Test void removesTheValidatedOccurrenceRatherThanAnEarlierSubstring() {
        var result=AgentAddressing.resolve("food oo give bread",pair());
        assertEquals(oo,result.target());
        assertEquals("food give bread",result.text());
    }
}
