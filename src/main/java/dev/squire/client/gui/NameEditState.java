package dev.squire.client.gui;

/** Keeps a rename draft through page refreshes, but never carries it to another companion. */
final class NameEditState {
    private String agent = "";
    private String saved = "";
    private String draft = "";

    void sync(String agentId, String name) {
        if (!agent.equals(agentId) || !dirty() || draft.equals(name)) {
            draft = name;
        }
        agent = agentId;
        saved = name;
    }

    String draft() { return draft; }
    boolean dirty() { return !draft.equals(saved); }
    void edit(String value) { draft = value; }
    void reset() { draft = saved; }
}
