package io.opaa.query.toolloop;

/** A registered tool as the agent editor lists it: its name and its rights class. */
public record AgentToolInfo(String name, ToolRightsClass rightsClass) {}
