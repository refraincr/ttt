CREATE TABLE tb_keyword_rule (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 terms JSON NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 1
);
INSERT INTO tb_keyword_rule(name,terms) VALUES
 ('单词：扬言',JSON_ARRAY('扬言')),('单词：杀人',JSON_ARRAY('杀人')),('单词：跳楼',JSON_ARRAY('跳楼')),
 ('组合：婚＋欠',JSON_ARRAY('婚','欠')),('组合：婚＋钱',JSON_ARRAY('婚','钱'));
