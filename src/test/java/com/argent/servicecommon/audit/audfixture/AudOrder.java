package com.argent.servicecommon.audit.audfixture;

import com.argent.servicecommon.audit.AuditIgnore;
import com.argent.servicecommon.audit.Audited;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Тестовый аудируемый заказ: простое поле, скрытое поле, enum и ссылка. */
@Entity
@Audited
@Table(name = "aud_order")
@Getter
@Setter
public class AudOrder {

    /** Статусы тестового заказа. */
    public enum Status { NEW, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String number;

    @AuditIgnore
    private String secret;

    @Enumerated(EnumType.STRING)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private AudRegion region;

    @Version
    private Long version;
}
