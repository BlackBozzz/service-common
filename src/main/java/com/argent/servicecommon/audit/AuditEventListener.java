package com.argent.servicecommon.audit;

import jakarta.persistence.Table;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.HashSet;
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
        for (int index : changedProperties(event, types)) {
            // Версия меняется при любом обновлении — отдельная строка для неё лишняя; коллекции не пишутся.
            if (index == versionIndex || types[index].isCollectionType()) {
                continue;
            }
            String oldValue = render(factory, types[index], oldState == null ? null : oldState[index]);
            String newValue = render(factory, types[index], newState == null ? null : newState[index]);
            if (audited.isIgnored(names[index])) {
                oldValue = MASK;
                newValue = MASK;
            }
            writer.write(audited.table(), idOf(event.getId()), "UPDATE", names[index], oldValue, newValue);
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
                .filter(index -> !Objects.equals(render(factory, types[index], oldState[index]),
                        render(factory, types[index], newState[index])))
                .toArray();
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
        Table table = AnnotatedElementUtils.findMergedAnnotation(type, Table.class);
        String tableName = table != null && StringUtils.hasText(table.name())
                ? table.name()
                : physicalTableName(persister.getTableName());
        Set<String> ignored = new HashSet<>();
        ReflectionUtils.doWithFields(type, field -> ignored.add(field.getName()),
                (Field field) -> field.isAnnotationPresent(AuditIgnore.class));
        return new AuditedClass(true, tableName, Set.copyOf(ignored));
    }

    /** Имя таблицы без схемы/каталога и кавычек: {@code "it"."aud_order"} → {@code aud_order}. */
    static String physicalTableName(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        return name.replaceAll("[\"`\\[\\]]", "");
    }

    /** Закэшированные сведения о классе сущности. */
    private record AuditedClass(boolean enabled, String table, Set<String> ignoredFields) {

        static final AuditedClass DISABLED = new AuditedClass(false, null, Set.of());

        boolean isIgnored(String property) {
            return ignoredFields.contains(property);
        }
    }
}
