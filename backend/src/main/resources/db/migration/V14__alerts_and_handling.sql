CREATE TABLE tb_alert (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 person_id BIGINT UNSIGNED NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 level VARCHAR(16) NOT NULL,
 snapshot JSON NOT NULL,
 basis_hash VARCHAR(64) NOT NULL,
 new_basis BOOLEAN NOT NULL DEFAULT 1,
 version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 reviewed_at DATETIME NULL,
 closed_at DATETIME NULL,
 INDEX idx_alert_person(person_id,state),
 INDEX idx_alert_pending(state,reviewed_at,created_at)
);
CREATE TABLE tb_alert_review (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 alert_id BIGINT UNSIGNED NOT NULL,
 action VARCHAR(16) NOT NULL,
 conclusion VARCHAR(2000) NOT NULL,
 actor VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL,
 snapshot JSON NOT NULL,
 FOREIGN KEY(alert_id) REFERENCES tb_alert(id)
);
CREATE TABLE tb_alert_settings (id INT PRIMARY KEY, backlog_days INT NOT NULL, version BIGINT NOT NULL DEFAULT 1);
INSERT INTO tb_alert_settings VALUES (1,7,1);
CREATE TABLE tb_handling_task (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 alert_id BIGINT UNSIGNED NOT NULL,
 person_id BIGINT UNSIGNED NOT NULL,
 department_id BIGINT UNSIGNED NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED',
 version BIGINT NOT NULL DEFAULT 1,
 title VARCHAR(200) NOT NULL,
 instruction TEXT NOT NULL,
 contact VARCHAR(500) NOT NULL,
 summary TEXT NOT NULL,
 deadline DATETIME NOT NULL,
 dispatch_snapshot JSON NOT NULL,
 dispatched_by VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL,
 submitted_at DATETIME NULL,
 closed_at DATETIME NULL,
 FOREIGN KEY(alert_id) REFERENCES tb_alert(id),
 INDEX idx_task_department(department_id,state)
);
CREATE TABLE tb_task_event (
 task_id BIGINT UNSIGNED NOT NULL,
 event_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(task_id,event_id),
 FOREIGN KEY(task_id) REFERENCES tb_handling_task(id),
 FOREIGN KEY(event_id) REFERENCES tb_effective_event(id)
);
CREATE TABLE tb_task_note (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT UNSIGNED NOT NULL,
 action VARCHAR(16) NOT NULL,
 content TEXT NOT NULL,
 actor VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL,
 completed_events JSON NOT NULL,
 FOREIGN KEY(task_id) REFERENCES tb_handling_task(id)
);
CREATE TABLE tb_task_attachment (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT UNSIGNED NULL,
 note_id BIGINT UNSIGNED NULL,
 owner_username VARCHAR(100) NOT NULL,
 stage VARCHAR(16) NOT NULL,
 file_name VARCHAR(200) NOT NULL,
 content MEDIUMBLOB NOT NULL,
 created_at DATETIME NOT NULL,
 FOREIGN KEY(task_id) REFERENCES tb_handling_task(id),
 FOREIGN KEY(note_id) REFERENCES tb_task_note(id)
);
ALTER TABLE tb_effective_event ADD COLUMN is_completed BOOLEAN NOT NULL DEFAULT 0, ADD COLUMN completed_at DATETIME NULL;
