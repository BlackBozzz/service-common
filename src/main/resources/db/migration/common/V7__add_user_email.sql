-- Email необязателен: уже созданные пользователи сохраняются без адреса.
ALTER TABLE common_user ADD COLUMN email VARCHAR(254);
-- Регистронезависимая уникальность (NULL не мешает существующим пользователям).
CREATE UNIQUE INDEX uk_common_user_email_ci
    ON common_user (lower(email)) WHERE email IS NOT NULL;
