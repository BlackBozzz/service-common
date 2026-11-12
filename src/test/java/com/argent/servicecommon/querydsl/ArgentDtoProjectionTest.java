package com.argent.servicecommon.querydsl;

import com.argent.servicecommon.auth.entity.QUser;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.Expressions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.argent.servicecommon.querydsl.ArgentQuerydsl.selectDto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgentDtoProjectionTest {

    private final QUser user = QUser.user;

    @Test
    void supportsSimpleAndDeepAliasesAndStripsAliasesFromDatabaseProjection() {
        var projection = selectDto(UserView.class,
                user.id,
                user.login.as("loginName"),
                user.displayName.as("dto$insideDto$insideDtoField"));

        UUID uuid = UUID.randomUUID();
        UserView mapped = projection.newInstance(uuid, "robot", "Robot");

        assertThat(mapped.id).isEqualTo(uuid);
        assertThat(mapped.loginName).isEqualTo("robot");
        assertThat(mapped.dto.insideDto.insideDtoField).isEqualTo("Robot");
        assertThat(projection.getArgs()).containsExactly(user.id, user.login, user.displayName);
    }

    @Test
    void doesNotCreateNestedObjectsWhenJoinedValueIsNull() {
        var projection = selectDto(UserView.class,
                user.login.as("loginName"),
                user.displayName.as("dto$insideDto$insideDtoField"));

        UserView mapped = projection.newInstance("login", null);

        assertThat(mapped.loginName).isEqualTo("login");
        assertThat(mapped.dto).isNull();
    }

    @Test
    void acceptsSelectionsFromList() {
        List<Expression<?>> selections = List.of(user.login.as("loginName"));
        UserView result = selectDto(UserView.class, selections).newInstance("test");
        assertThat(result.loginName).isEqualTo("test");
    }

    @Test
    void demandsAliasForComputedExpression() {
        assertThatThrownBy(() -> selectDto(UserView.class, user.login.concat("-suffix")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".as");
    }

    @Test
    void detectsDuplicateAliasesAndUnknownFields() {
        assertThatThrownBy(() -> selectDto(UserView.class,
                user.login.as("loginName"), user.displayName.as("loginName")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");

        assertThatThrownBy(() -> selectDto(UserView.class, user.login.as("unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void detectsInvalidNestedAliasAndWrongType() {
        assertThatThrownBy(() -> selectDto(UserView.class, user.login.as("dto$$insideDtoField")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid");

        assertThatThrownBy(() -> selectDto(UserView.class, user.login.as("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires");
    }

    @Test
    void supportsAliasedCalculatedExpression() {
        var expression = user.login.concat("_suffix").as("loginName");
        var projection = selectDto(UserView.class, expression);
        assertThat(projection.newInstance("test_suffix").loginName).isEqualTo("test_suffix");
    }

    static class UserView {
        private UUID id;
        private String loginName;
        private OuterDto dto;
    }

    static class OuterDto {
        private InnerDto insideDto;
    }

    static class InnerDto {
        private String insideDtoField;
    }
}
