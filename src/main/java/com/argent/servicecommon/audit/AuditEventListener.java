package com.argent.servicecommon.audit;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.CompositeType;
import org.hibernate.type.Type;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Слушатель Hibernate, пишущий журнал для сущностей с {@link Audited}; регистрируется через
 * {@link AuditHibernateIntegrator}, а не как бин. Вызывается до коммита: строка журнала пишется в той же
 * транзакции и откатывается вместе с изменением.
 */
public class AuditEventListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** Значение, которым заменяется содержимое полей с {@link AuditIgnore}. */
    public static final String MASK = "***";

    private static final Map<Class<?>, Set<String>> IGNORED_FIELDS = new ConcurrentHashMap<>();

    private final AuditWriter writer;
    private final Map<Class<?>, AuditedClass> classes = new ConcurrentHashMap<>();

    public AuditEventListener(AuditWriter writer) {
        this.writer = Objects.requireNonNull(writer);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        AuditedClass audited = describe(event.getPersister());
        if (audited != null) {
            writer.write(audited.table(), idOf(event.getId()), "INSERT", null, null, null);
        }
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        EntityPersister persister = event.getPersister();
        AuditedClass audited = describe(persister);
        if (audited == null) {
            return;
        }
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        int versionIndex = persister.isVersioned() ? persister.getVersionPropertyIndex() : -1;
        Object[] oldState = event.getOldState();
        Object[] newState = event.getState();
        SessionFactoryImplementor factory = event.getFactory();
        String id = idOf(event.getId());
        for (int index : changedProperties(event, types)) {
            // Версия меняется при любом обновлении — отдельная строка для неё лишняя; коллекции не пишутся.
            if (index == versionIndex || types[index].isCollectionType()) {
                continue;
            }
            writeChange(factory, audited.table(), id, names[index], types[index],
                    oldState == null ? null : oldState[index], newState == null ? null : newState[index],
                    audited.isIgnored(names[index]));
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        AuditedClass audited = describe(event.getPersister());
        if (audited != null) {
            writer.write(audited.table(), idOf(event.getId()), "DELETE", null, null, null);
        }
    }

    /** Вызывать сразу после SQL-операции в текущей транзакции, не дожидаясь коммита. */
    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    /**
     * Строка UPDATE для изменённого свойства. Встроенный объект ({@code @Embedded}) раскладывается на подполя:
     * по строке на каждое изменённое подполе с именем {@code поле.подполе} (вложенные — {@code a.b.c});
     * {@link AuditIgnore} на подполе или на всём встроенном объекте заменяет значения на {@link #MASK}.
     */
    private void writeChange(SessionFactoryImplementor factory, String table, String id, String field, Type type,
                             Object oldValue, Object newValue, boolean ignored) {
        if (type.isComponentType() && type instanceof CompositeType component) {
            String[] subNames = component.getPropertyNames();
            Type[] subTypes = component.getSubtypes();
            Object[] oldValues = componentValues(component, oldValue);
            Object[] newValues = componentValues(component, newValue);
            Set<String> ignoredSub = ignoredFields(component.getReturnedClass());
            for (int sub = 0; sub < subNames.length; sub++) {
                if (subTypes[sub].isCollectionType()
                        || sameValue(factory, subTypes[sub], oldValues[sub], newValues[sub])) {
                    continue;
                }
                writeChange(factory, table, id, field + "." + subNames[sub], subTypes[sub], oldValues[sub],
                        newValues[sub], ignored || ignoredSub.contains(subNames[sub]));
            }
            return;
        }
        String oldText = ignored ? MASK : render(factory, type, oldValue);
        String newText = ignored ? MASK : render(factory, type, newValue);
        writer.write(table, id, "UPDATE", field, oldText, newText);
    }

    /**
     * Индексы изменённых свойств. Обычно их считает Hibernate; если он их не передал (например, при
     * обновлении отсоединённой сущности), изменения определяются сравнением состояний.
     */
    private static int[] changedProperties(PostUpdateEvent event, Type[] types) {
        if (event.getDirtyProperties() != null) {
            return event.getDirtyProperties();
        }
        Object[] oldState = event.getOldState();
        Object[] newState = event.getState();
        if (oldState == null || newState == null) {
            return new int[0];
        }
        SessionFactoryImplementor factory = event.getFactory();
        return IntStream.range(0, types.length)
                .filter(index -> !types[index].isCollectionType())
                .filter(index -> !sameValue(factory, types[index], oldState[index], newState[index]))
                .toArray();
    }

    /** Значения совпадают: встроенные объекты сравниваются по подполям, остальные — по строковому виду. */
    private static boolean sameValue(SessionFactoryImplementor factory, Type type, Object oldValue, Object newValue) {
        if (type.isComponentType() && type instanceof CompositeType component) {
            Object[] oldValues = componentValues(component, oldValue);
            Object[] newValues = componentValues(component, newValue);
            Type[] subTypes = component.getSubtypes();
            for (int sub = 0; sub < subTypes.length; sub++) {
                if (!subTypes[sub].isCollectionType()
                        && !sameValue(factory, subTypes[sub], oldValues[sub], newValues[sub])) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(render(factory, type, oldValue), render(factory, type, newValue));
    }

    private static Object[] componentValues(CompositeType component, Object value) {
        return value == null ? new Object[component.getSubtypes().length] : component.getPropertyValues(value);
    }

    /** Строковое значение свойства: id для ссылки на сущность, иначе {@code toString()}. */
    private static String render(SessionFactoryImplementor factory, Type type, Object value) {
        if (value == null) {
            return null;
        }
        if (type.isEntityType()) {
            Object id = factory.getPersistenceUnitUtil().getIdentifier(value);
            return id == null ? null : id.toString();
        }
        return value.toString();
    }

    private static String idOf(Object id) {
        return id == null ? null : id.toString();
    }

    /** Описание аудируемого класса или {@code null}, если класс без {@link Audited}. */
    private AuditedClass describe(EntityPersister persister) {
        Class<?> type = persister.getMappedClass();
        if (type == null) {
            return null;
        }
        AuditedClass audited = classes.computeIfAbsent(type, key -> inspect(key, persister));
        return audited.enabled() ? audited : null;
    }

    private static AuditedClass inspect(Class<?> type, EntityPersister persister) {
        if (!type.isAnnotationPresent(Audited.class)) {
            return AuditedClass.DISABLED;
        }
        // Физическое имя таблицы (после стратегии именования), а не @Table(name) дословно: @Table(name = "AuxUpper")
        // даёт таблицу aux_upper, и журнал должен читаться через /api/audit?entityType=aux_upper.
        return new AuditedClass(true, physicalTableName(persister.getTableName()), ignoredFields(type));
    }

    /** Имена полей класса (с суперклассами), помеченных {@link AuditIgnore}. */
    private static Set<String> ignoredFields(Class<?> type) {
        if (type == null) {
            return Set.of();
        }
        return IGNORED_FIELDS.computeIfAbsent(type, key -> {
            Set<String> ignored = new HashSet<>();
            ReflectionUtils.doWithFields(key, field -> ignored.add(field.getName()),
                    (Field field) -> field.isAnnotationPresent(AuditIgnore.class));
            return Set.copyOf(ignored);
        });
    }

    /**
     * Имя таблицы без схемы/каталога: {@code "it"."aud_order"} → {@code aud_order}. Имя в кавычках сохраняет регистр
     * (кавычки снимаются), имя без кавычек приводится к нижнему регистру — так его хранит PostgreSQL.
     */
    static String physicalTableName(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        String unquoted = name.replaceAll("[\"`\\[\\]]", "");
        return unquoted.equals(name) ? name.toLowerCase(Locale.ROOT) : unquoted;
    }

    /** Закэшированные сведения о классе сущности. */
    private record AuditedClass(boolean enabled, String table, Set<String> ignoredFields) {

        static final AuditedClass DISABLED = new AuditedClass(false, null, Set.of());

        boolean isIgnored(String property) {
            return ignoredFields.contains(property);
        }
    }
}
