# Документация OpenAPI и каталог админки

Основное руководство находится в `DEVELOPER_GUIDE/backend/13_openapi_docs.md` в корне AtomSkills.

- Современные аннотации: `@Tag`, `@Operation`, `@Schema`, `@Parameter`, `@ApiResponse`.
- `@ArgentApiAction("docs.read")` — описание необходимого action; само по себе не защищает endpoint.
- Роли берутся из текущих грантов `common_role` (действие и `admin`), показываются в `x-argent-roles`, в Swagger и каталоге админки.
- `GET /api/argent-docs/roles` всегда читает актуальные роли, без ожидания пересоздания OpenAPI.
- DTO и БД: `/api/argent-docs/dtos`, `/api/argent-docs/database`, без чтения строк таблиц.
- `argent.docs.enabled=false` отключает каталоги и OpenAPI-кастомизации Argent.
- `springdoc.cache.disabled=true` полезно, когда требуется немедленное обновление ролей в Swagger UI (не только в админке).

Используйте `@Schema(title="Русское название", description="Пояснение")` и сохраняйте стабильные технические имена полей.
