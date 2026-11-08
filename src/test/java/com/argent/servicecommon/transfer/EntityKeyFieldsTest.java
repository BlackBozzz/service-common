package com.argent.servicecommon.transfer;

import com.argent.servicecommon.status.entity.CommonStatus;
import com.argent.servicecommon.status.entity.CommonStatusTransition;
import com.argent.servicecommon.transfer.imp.EntityKeyFields;
import com.argent.servicecommon.transfer.impfixture.ImpCountry;
import com.argent.servicecommon.transfer.impfixture.ImpWithCollection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ключевые поля UPSERT по умолчанию из аннотаций сущности. */
class EntityKeyFieldsTest {

    @Test
    void shouldUseUniqueColumn() {
        assertThat(EntityKeyFields.derive(ImpCountry.class)).containsExactly("name");
    }

    @Test
    void shouldMapUniqueConstraintColumnsToFields() {
        assertThat(EntityKeyFields.derive(CommonStatus.class)).containsExactly("statusType", "code");
        assertThat(EntityKeyFields.derive(CommonStatusTransition.class))
                .containsExactly("statusType", "fromStatus", "toStatus");
    }

    @Test
    void shouldReturnEmptyWithoutUniqueKey() {
        assertThat(EntityKeyFields.derive(ImpWithCollection.class)).isEmpty();
    }
}
