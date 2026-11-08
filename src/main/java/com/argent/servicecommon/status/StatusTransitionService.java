package com.argent.servicecommon.status;

import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.security.exception.GrantsAccessDeniedException;
import com.argent.servicecommon.status.dto.CommonStatusDto;
import com.argent.servicecommon.status.dto.CommonStatusTypeDto;
import com.argent.servicecommon.status.entity.CommonStatus;
import com.argent.servicecommon.status.entity.CommonStatusTransition;
import com.argent.servicecommon.status.entity.CommonStatusType;
import com.argent.servicecommon.status.repository.CommonStatusRepository;
import com.argent.servicecommon.status.repository.CommonStatusTransitionRepository;
import com.argent.servicecommon.status.repository.CommonStatusTypeRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Проверка переходов статусов по справочнику переходов; поле статуса в сущности приложение меняет само.
 *
 * <p>{@code noRollbackFor}: отказы (404/409/403) не помечают транзакцию вызывающего на откат — исключение можно
 * поймать и продолжить работу в той же транзакции.</p>
 */
@Transactional(readOnly = true,
        noRollbackFor = {EntityNotFoundException.class, IllegalStateException.class, AccessDeniedException.class})
public class StatusTransitionService {

    private final CommonStatusTypeRepository types;
    private final CommonStatusRepository statuses;
    private final CommonStatusTransitionRepository transitions;

    public StatusTransitionService(CommonStatusTypeRepository types, CommonStatusRepository statuses,
                                   CommonStatusTransitionRepository transitions) {
        this.types = Objects.requireNonNull(types);
        this.statuses = Objects.requireNonNull(statuses);
        this.transitions = Objects.requireNonNull(transitions);
    }

    /**
     * Проверяет, что переход предусмотрен и доступен текущему пользователю ({@link CurrentUser#hasAction} для
     * {@code requiredAction}; аноним не проходит); {@code fromCode == null} — создание записи.
     *
     * @throws EntityNotFoundException    тип или статус не найден (404)
     * @throws IllegalStateException      переход не предусмотрен (409)
     * @throws GrantsAccessDeniedException нет действия, которого требует переход (403)
     */
    public void assertAllowed(String typeCode, String fromCode, String toCode) {
        CommonStatusType type = type(typeCode);
        CommonStatus from = fromCode == null ? null : status(type, fromCode);
        CommonStatus to = status(type, toCode);

        CommonStatusTransition transition = transitionsFrom(type, from)
                .filter(candidate -> candidate.getToStatus().getId().equals(to.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Переход "
                        + (fromCode == null ? "(создание)" : fromCode) + "→" + toCode + " не предусмотрен"));

        if (!permitted(transition)) {
            throw new GrantsAccessDeniedException("Для перехода требуется действие " + transition.getRequiredAction());
        }
    }

    /**
     * Статусы, доступные текущему пользователю из {@code fromCode} ({@code null} — при создании), по {@code sortOrder}.
     *
     * @throws EntityNotFoundException тип или статус не найден
     */
    public List<CommonStatusDto> allowedFrom(String typeCode, String fromCode) {
        CommonStatusType type = type(typeCode);
        CommonStatus from = fromCode == null ? null : status(type, fromCode);
        CommonStatusTypeDto typeDto = new CommonStatusTypeDto(type.getId(), type.getCode(), type.getName());

        return transitionsFrom(type, from)
                .filter(this::permitted)
                .map(CommonStatusTransition::getToStatus)
                .distinct()
                .sorted(Comparator.comparing(CommonStatus::getSortOrder).thenComparing(CommonStatus::getCode))
                .map(status -> new CommonStatusDto(status.getId(), typeDto, status.getCode(), status.getName(),
                        status.getSortOrder(), status.getInitialStatus(), status.getFinalStatus()))
                .toList();
    }

    /**
     * Переходы типа из указанного статуса. Переходы, у которых исходный или целевой статус относится
     * к другому типу, считаются некорректными и не учитываются.
     */
    private Stream<CommonStatusTransition> transitionsFrom(CommonStatusType type, CommonStatus from) {
        UUID fromId = from == null ? null : from.getId();
        return transitions.findByStatusTypeId(type.getId()).stream()
                .filter(transition -> Objects.equals(idOf(transition.getFromStatus()), fromId))
                .filter(transition -> belongsTo(transition.getToStatus(), type)
                        && (transition.getFromStatus() == null || belongsTo(transition.getFromStatus(), type)));
    }

    private boolean permitted(CommonStatusTransition transition) {
        String action = transition.getRequiredAction();
        return action == null || action.isBlank() || CurrentUser.hasAction(action);
    }

    private CommonStatusType type(String typeCode) {
        return types.findByCode(typeCode)
                .orElseThrow(() -> new EntityNotFoundException("Тип статуса не найден: " + typeCode));
    }

    private CommonStatus status(CommonStatusType type, String code) {
        return statuses.findByStatusTypeIdAndCode(type.getId(), code)
                .orElseThrow(() -> new EntityNotFoundException("Статус не найден: " + code));
    }

    private static boolean belongsTo(CommonStatus status, CommonStatusType type) {
        return status.getStatusType().getId().equals(type.getId());
    }

    private static UUID idOf(CommonStatus status) {
        return status == null ? null : status.getId();
    }
}
