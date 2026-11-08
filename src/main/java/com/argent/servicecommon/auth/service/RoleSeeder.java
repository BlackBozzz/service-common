package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties.RolesFileMode;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Загружает стартовые роли приложения из {@code argent.auth.roles-file} при запуске.
 *
 * <p>Файл — JSON-массив ролей {@code {code, name, description?, grants}}; необязателен: если ресурса нет,
 * в журнал пишется сообщение и ничего не происходит. Весь файл проверяется до записи в БД; любая ошибка
 * (формат JSON, код роли, имя, гранты) прерывает запуск {@link IllegalStateException} с именем файла
 * и кодом роли.</p>
 *
 * <p>Режим {@link RolesFileMode#CREATE} создаёт только роли, которых ещё нет по коду, чтобы правки
 * администратора переживали перезапуск. Режим {@link RolesFileMode#OVERWRITE} дополнительно приводит
 * имя, описание и гранты существующих несистемных ролей к файлу. Роли, которых нет в файле, не меняются.
 * Признак {@code system} из файла игнорируется; роль {@code ADMIN} в файле игнорируется — её определяет
 * библиотека ({@link BootstrapAdminInitializer}). Загрузка выполняется одной транзакцией.</p>
 */
@Order(RoleSeeder.ORDER)
public class RoleSeeder implements ApplicationRunner, Ordered {

    /** Порядок среди {@link ApplicationRunner}: до {@link BootstrapAdminInitializer#ORDER}. */
    public static final int ORDER = 250;

    private static final Logger log = LoggerFactory.getLogger(RoleSeeder.class);

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");
    private static final int NAME_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 1000;
    private static final Set<String> FIELDS = Set.of("code", "name", "description", "grants", "system");

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final String location;
    private final RolesFileMode mode;
    private final ResourceLoader resourceLoader;
    private final RoleRepository roleRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param location       расположение файла ролей в формате {@link ResourceLoader}
     *                       (например {@code classpath:argent-roles.json} или {@code file:./roles.json})
     * @param mode           режим загрузки
     * @param resourceLoader загрузчик ресурса
     * @param roleRepository репозиторий ролей
     * @param transactionManager менеджер транзакций
     */
    public RoleSeeder(String location, RolesFileMode mode, ResourceLoader resourceLoader,
            RoleRepository roleRepository, PlatformTransactionManager transactionManager) {
        this.location = Objects.requireNonNull(location);
        this.mode = Objects.requireNonNull(mode);
        this.resourceLoader = Objects.requireNonNull(resourceLoader);
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * Читает файл ролей, проверяет его целиком и применяет в одной транзакции.
     *
     * @throws IllegalStateException если файл некорректен
     */
    public void seed() {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.info("Файл ролей не найден: {}", location);
            return;
        }
        List<RoleDefinition> definitions = read(resource);
        transactionTemplate.executeWithoutResult(status -> definitions.forEach(this::apply));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private void apply(RoleDefinition definition) {
        Role existing = roleRepository.findByCode(definition.code()).orElse(null);
        if (existing == null) {
            Role role = new Role();
            role.setCode(definition.code());
            role.setSystem(false);
            fill(role, definition);
            roleRepository.save(role);
            log.info("Создана роль {} из файла ролей {}", definition.code(), location);
            return;
        }
        if (mode != RolesFileMode.OVERWRITE) {
            return;
        }
        if (existing.isSystem()) {
            log.warn("Системная роль {} из файла ролей {} не изменена", definition.code(), location);
            return;
        }
        fill(existing, definition);
        roleRepository.save(existing);
        log.info("Роль {} приведена к файлу ролей {}", definition.code(), location);
    }

    private static void fill(Role role, RoleDefinition definition) {
        role.setName(definition.name());
        role.setDescription(definition.description());
        role.setGrants(definition.grants());
    }

    private List<RoleDefinition> read(Resource resource) {
        JsonNode root;
        try (InputStream input = resource.getInputStream()) {
            root = MAPPER.readTree(input);
        } catch (IOException | JacksonException exception) {
            throw new IllegalStateException("Ошибка в файле ролей " + location + ": некорректный JSON ("
                    + exception.getMessage() + ")", exception);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Ошибка в файле ролей " + location + ": ожидается JSON-массив ролей");
        }

        List<RoleDefinition> definitions = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            index++;
            String code = node.isObject() ? text(node, "code") : null;
            String label = code == null ? "#" + index : code;
            if (!node.isObject()) {
                throw roleError(label, "ожидается JSON-объект");
            }
            if (code == null || !CODE.matcher(code).matches()) {
                throw roleError(label, "код роли должен соответствовать " + CODE.pattern());
            }
            if (!codes.add(code)) {
                throw roleError(label, "роль указана в файле повторно");
            }
            if (GrantConstants.ADMIN_ROLE_CODE.equals(code)) {
                log.warn("Роль {} в файле ролей {} игнорируется: её определяет библиотека", code, location);
                continue;
            }
            definitions.add(parseRole(code, node));
        }
        return definitions;
    }

    private RoleDefinition parseRole(String code, JsonNode node) {
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!FIELDS.contains(property.getKey())) {
                throw roleError(code, "неизвестное поле " + property.getKey());
            }
        }
        if (node.has("system")) {
            log.warn("Поле system роли {} в файле ролей {} игнорируется", code, location);
        }

        String name = text(node, "name");
        if (name == null || name.isBlank()) {
            throw roleError(code, "не задано имя роли");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw roleError(code, "имя роли длиннее " + NAME_MAX_LENGTH + " символов");
        }

        JsonNode descriptionNode = node.get("description");
        String description = null;
        if (descriptionNode != null && !descriptionNode.isNull()) {
            if (!descriptionNode.isString()) {
                throw roleError(code, "описание роли должно быть строкой");
            }
            description = descriptionNode.stringValue();
            if (description.length() > DESCRIPTION_MAX_LENGTH) {
                throw roleError(code, "описание роли длиннее " + DESCRIPTION_MAX_LENGTH + " символов");
            }
        }

        JsonNode grantsNode = node.get("grants");
        String grants;
        try {
            grants = GrantsCodec.write(GrantsCodec.parse(grantsNode == null ? null : grantsNode.toString()));
        } catch (ValidationErrorsException exception) {
            throw roleError(code, String.join("; ", exception.getErrors().values()));
        }
        return new RoleDefinition(code, name.trim(), description, grants);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private IllegalStateException roleError(String code, String detail) {
        return new IllegalStateException("Ошибка в файле ролей " + location + ", роль " + code + ": " + detail);
    }

    /** Проверенная роль из файла; {@code grants} — нормализованный JSON. */
    private record RoleDefinition(String code, String name, String description, String grants) {
    }
}
