CREATE TABLE tb_rule_preview_person (
 preview_id BIGINT NOT NULL,
 person_id BIGINT UNSIGNED NOT NULL,
 before_data JSON NOT NULL,
 after_data JSON NOT NULL,
 PRIMARY KEY(preview_id,person_id)
);
