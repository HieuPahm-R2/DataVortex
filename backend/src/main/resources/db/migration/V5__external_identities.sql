-- OIDC users have no application password. Existing BCrypt hashes remain intact.
ALTER TABLE users MODIFY password_hash VARCHAR(100) NULL;

CREATE TABLE user_identities (
  issuer VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  user_id CHAR(36) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (issuer, subject),
  INDEX identity_user (user_id),
  FOREIGN KEY (user_id) REFERENCES users(id)
);
