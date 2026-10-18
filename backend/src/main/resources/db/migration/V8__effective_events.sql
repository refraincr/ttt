CREATE TABLE tb_effective_event (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 person_id BIGINT UNSIGNED NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(person_id) REFERENCES tb_person(id)
);
CREATE TABLE tb_event_source_link (
 record_id BIGINT UNSIGNED PRIMARY KEY,
 effective_event_id BIGINT UNSIGNED NOT NULL,
 FOREIGN KEY(record_id) REFERENCES tb_input_record(id),
 FOREIGN KEY(effective_event_id) REFERENCES tb_effective_event(id),
 INDEX idx_effective_sources(effective_event_id)
);
CREATE TABLE tb_duplicate_decision (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 record_id BIGINT UNSIGNED NOT NULL,
 effective_event_id BIGINT UNSIGNED NOT NULL,
 decision VARCHAR(24) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 actor_username VARCHAR(50) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(record_id) REFERENCES tb_input_record(id),
 FOREIGN KEY(effective_event_id) REFERENCES tb_effective_event(id)
);
