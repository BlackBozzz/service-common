# Argent Mail — универсальный почтовый модуль

Модуль `service-common` для отправки системных писем через SMTP. Совместим с Java 21 / Spring Boot 4.1.2.
Настройки SMTP **не хранятся в Java-коде**, пароль передаётся через переменные окружения.

## Возможности

- `ArgentMailService` — синхронная отправка (`sendTo*`).
- `ArgentQueuedMailService` — фоновая доставка через существующую PostgreSQL-очередь `argent-mail` (`queueTo*`).
- Получатели: конкретный email, список email, один UUID пользователя, список UUID, код роли,
  список ролей, все активные пользователи с email.
- HTML и plain text: `ArgentMailMessage.html(...)` и `.text(...)`.
- Пользователи без email или отключённые исключаются; повторяющиеся адреса дедуплицируются.
- Каждый получатель получает **отдельное письмо** (адреса других получателей не раскрываются).
- У массовых отправок есть лимит `argent.mail.max-recipients-per-call` (по умолчанию 5000).
- Почта отключена по умолчанию (`argent.mail.enabled=false`). REST-эндпоинт массовой отправки **не создаётся**.

## 1. Конфигурация

В **приложении-хосте** (`main-backend/src/main/resources/application.properties` или YAML)
добавить содержимое файла `main-backend/config/mail.properties.example` из патча. В рабочем окружении задать:

```dotenv
ARGENT_MAIL_ENABLED=true
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=mailer@example.com
SMTP_PASSWORD=из-секретного-хранилища
ARGENT_MAIL_FROM=mailer@example.com
ARGENT_MAIL_FROM_NAME=AtomSkills
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_SSL=false
```

Для SMTPS/465 обычно нужны `SMTP_STARTTLS=false`, `SMTP_SSL=true`. Эти значения могут отличаться
у конкретного провайдера; проверяйте его параметры. Никогда не добавляйте реальные SMTP-секреты в Git.

При `ARGENT_MAIL_ENABLED=false` почтовые сервисы не регистрируются и SMTP не требуется.
При `true` отсутствие JavaMailSender или некорректный `ARGENT_MAIL_FROM` приводит к явной ошибке при запуске.

## 2. Отправка в бизнес-логике

```java
import com.argent.servicecommon.mail.ArgentMailMessage;
import com.argent.servicecommon.mail.ArgentMailService;
import com.argent.servicecommon.mail.ArgentQueuedMailService;
import java.util.List;
import java.util.UUID;

public class OrderNotificationService {
    private final ArgentQueuedMailService queuedMail;
    private final ArgentMailService mail;

    public OrderNotificationService(ArgentQueuedMailService queuedMail, ArgentMailService mail) {
        this.queuedMail = queuedMail;
        this.mail = mail;
    }

    public void notifyManagers(UUID managerId, UUID operatorId) {
        var message = ArgentMailMessage.html("Новый заказ", "<p>Появился новый заказ</p>");
        queuedMail.queueToRole("MANAGER", message, "new-order-123");
        queuedMail.queueToUserIds(List.of(managerId, operatorId), message);
    }

    public void urgentMessage(String email) {
        var result = mail.sendToEmail(email, ArgentMailMessage.text("Событие", "Детали события"));
        if (result.failed() > 0) {
            // Отреагировать на неудачу синхронной отправки.
        }
    }
}
```

Не используйте один и тот же `campaignKey` для разных рассылок: он предотвращает повторную постановку
задач на те же адреса и не учитывает содержимое письма. Если операции запускаются в бизнес-транзакции,
созданные задания очереди войдут в ту же транзакцию.

### Методы

| Синхронно (`ArgentMailService`) | В очереди (`ArgentQueuedMailService`) |
|---|---|
| `sendToEmail(email, message)` | `queueToEmail(email, message)` |
| `sendToEmails(emails, message)` | `queueToEmails(emails, message)` |
| `sendToUserId(id, message)` | `queueToUserId(id, message)` |
| `sendToUserIds(ids, message)` | `queueToUserIds(ids, message)` |
| `sendToRole(roleCode, message)` | `queueToRole(roleCode, message)` |
| `sendToRoles(roleCodes, message)` | `queueToRoles(roleCodes, message)` |
| `sendToAllEnabledUsers(message)` | `queueToAllEnabledUsers(message)` |

Для `queueToRole`, `queueToEmails`, `queueToUserIds` есть перегрузка с третьим
аргументом `campaignKey` для дедупликации постановки заданий.

**Важно:** возвращённый `ArgentQueuedMailResult` означает, что задача записана в очередь,
а не что письмо уже доставлено. Статус виден в админке Argent Queue.

## 3. Пользователи и миграция

- Новая миграция `V7__add_user_email.sql` добавляет необязательное поле `common_user.email`
  и уникальный индекс без учёта регистра.
- Email теперь принимается при создании и редактировании пользователя через `POST /api/users` и
  `PUT /api/users/{id}`; показывается в админке. Для существующих пользователей email = NULL.
- При обновлении, если ключ `email` **отсутствует** / `null` — текущий адрес сохраняется;
  если передана пустая строка — адрес очищается.
- Все неактивные пользователи и пользователи без корректного email пропускаются при отправке по ролям/ID.
- Обновление email через административное API защищено существующей грантой `admin`.

## 4. Надёжность, безопасность, особенности

- Очередь даёт **at-least-once** обработку: если SMTP принял письмо, а воркер не успел записать
  `SUCCESS`, возможна повторная доставка при следующей попытке. Ни SMTP, ни очередь не гарантируют
  exactly-once доставку. Проверяйте потребность в дублях для критичных уведомлений.
- `campaignKey` защищает только от повторной **постановки** той же задачи; не от редкого повтора
  исполнения после потери lease.
- По ролям получатели выбираются на момент постановки задания; смена роли после этого не удалит
  уже созданное задание.
- Отправляйте транзакционные и разрешённые пользователями уведомления; для маркетинговых рассылок
  дополнительно нужны согласия, отписка, лимит скорости и соблюдение местных правил.
- Письма с вложениями, шаблонизация и UI создания кампаний пока не реализованы.
- В случае проблем с SMTP проверьте `ARGENT_MAIL_ENABLED`, `SMTP_HOST`, учётные данные,
  порт и настройки TLS. Для фоновых задач дополнительно `argent.queue.enabled` и таблицы `argent_queue*`.

## 5. Сборка

```powershell
cd D:\Projects\AtomSkills\service-common
.\mvnw.cmd clean install
cd ..\main-backend
.\mvnw.cmd spring-boot:run
```

После установки `service-common` обязательно обновите Maven-модель хоста, чтобы подтянулся
`spring-boot-starter-mail` транзитивно; повторного указания зависимости в `main-backend` не нужно.
После изменения фронтенда собрать и переустановить `@argent/webapp-common` обычным способом проекта.
