CREATE TABLE IF NOT EXISTS predict (
  word        VARCHAR(128) NOT NULL,
  category_id TINYINT      NOT NULL,
  cnt         INTEGER      NOT NULL DEFAULT 0,
  PRIMARY KEY (word, category_id)
);
ALTER TABLE predict
  ADD INDEX (word);