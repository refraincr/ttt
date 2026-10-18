CREATE INDEX idx_record_person_deleted ON tb_input_record(matched_person_id,is_deleted);
CREATE INDEX idx_adjustment_person ON tb_risk_adjustment(person_id,id);
CREATE INDEX idx_exclusion_event_active ON tb_keyword_exclusion(event_id,active,rule_id);
