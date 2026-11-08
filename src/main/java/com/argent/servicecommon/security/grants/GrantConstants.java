package com.argent.servicecommon.security.grants;

/**
 * Константы модели грантов: встроенная роль администратора и её права.
 */
public final class GrantConstants {

    /** Код системной роли администратора. */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    /** Отображаемое имя роли администратора. */
    public static final String ADMIN_ROLE_NAME = "Администратор";

    /**
     * Встроенное действие, дающее право управлять пользователями и ролями. Покрывает любые другие
     * действия при проверке «можно выдать» ({@link GrantsAuthorities#covers}): держатель {@code admin}
     * может выдавать роли с любыми действиями, но таблицы — только в пределах своих грантов таблиц.
     */
    public static final String ADMIN_ACTION = "admin";

    /** Гранты роли администратора: чтение и запись всех таблиц и действие {@value #ADMIN_ACTION}. */
    public static final String ADMIN_GRANTS = "{\"tables\":{\"*\":[\"read\",\"write\"]},\"actions\":[\"admin\"]}";

    private GrantConstants() {
    }
}
