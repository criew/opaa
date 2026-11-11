SET LOCAL lock_timeout = '5s';

-- The document templates an agent fills its result into; one template serves many agents. A
-- deleted template drops out with its row, a deleted agent takes its rows along.
CREATE TABLE agent_templates (
    agent_id uuid NOT NULL,
    template_id uuid NOT NULL,
    CONSTRAINT agent_templates_pkey PRIMARY KEY (agent_id, template_id),
    CONSTRAINT fk_agent_templates_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_templates_template FOREIGN KEY (template_id) REFERENCES document_templates(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_templates_template ON agent_templates USING btree (template_id);
