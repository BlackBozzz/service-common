package com.argent.servicecommon.queue.controller;

import com.argent.servicecommon.queue.dto.ArgentQueueDto;
import com.argent.servicecommon.queue.service.ArgentQueueService;
import com.argent.servicecommon.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Админское API: чтение — действие queue.read, управление — только встроенное admin. */
@RestController
@RequestMapping("/api/argent-queues")
public class ArgentQueueController {
    private final ArgentQueueService service;

    public ArgentQueueController(ArgentQueueService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@argentAccess.action('queue.read')")
    public List<ArgentQueueDto.Queue> queues() { return service.listQueues(); }

    @GetMapping("/{queueId}/tasks")
    @PreAuthorize("@argentAccess.action('queue.read')")
    public ArgentQueueDto.Page<ArgentQueueDto.Task> tasks(@PathVariable UUID queueId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Страница должна быть >= 0, размер 1–200");
        }
        return service.listTasks(queueId, page, size);
    }

    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("@argentAccess.action('queue.read')")
    public ArgentQueueDto.TaskDetails task(@PathVariable UUID taskId) {
        ArgentQueueDto.TaskDetails details = service.task(taskId);
        if (CurrentUser.hasAction("admin")) return details;
        // Операторам доступна диагностика ошибок, но не параметры задания и внутренний stack trace.
        return new ArgentQueueDto.TaskDetails(details.task(), null, details.attempts().stream()
                .map(attempt -> new ArgentQueueDto.Attempt(attempt.id(), attempt.attemptNo(),
                        attempt.startedAt(), attempt.finishedAt(), attempt.result(),
                        attempt.errorType(), attempt.errorMessage(), null))
                .toList());
    }

    @PostMapping("/{queueId}/pause")
    @PreAuthorize("@argentAccess.action('admin')")
    public void pause(@PathVariable UUID queueId) { service.pauseQueue(queueId); }

    @PostMapping("/{queueId}/resume")
    @PreAuthorize("@argentAccess.action('admin')")
    public void resume(@PathVariable UUID queueId) { service.resumeQueue(queueId); }

    @PostMapping("/{queueId}/run")
    @PreAuthorize("@argentAccess.action('admin')")
    public Map<String, Integer> runNow(@PathVariable UUID queueId) {
        return Map.of("rescheduled", service.runQueueNow(queueId));
    }

    @PatchMapping("/{queueId}/settings")
    @PreAuthorize("@argentAccess.action('admin')")
    public void settings(@PathVariable UUID queueId, @RequestBody ArgentQueueDto.QueueSettings settings) {
        service.setQueueAllowSkip(queueId, settings.allowSkip());
    }

    @PostMapping("/tasks/{taskId}/retry")
    @PreAuthorize("@argentAccess.action('admin')")
    public void retry(@PathVariable UUID taskId) { service.retryTask(taskId); }

    @PatchMapping("/tasks/{taskId}/priority")
    @PreAuthorize("@argentAccess.action('admin')")
    public void priority(@PathVariable UUID taskId, @RequestBody ArgentQueueDto.PriorityChange value) {
        service.setPriority(taskId, value.priority());
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @PreAuthorize("@argentAccess.action('admin')")
    public void cancel(@PathVariable UUID taskId) { service.cancelTask(taskId); }
}
