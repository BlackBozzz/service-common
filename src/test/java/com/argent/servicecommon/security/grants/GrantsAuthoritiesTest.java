package com.argent.servicecommon.security.grants;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет authorities из грантов, объединение ролей, покрытие прав и проверку таблиц с {@code *}.
 */
class GrantsAuthoritiesTest {

    @Test
    void shouldBuildAuthoritiesWithStar() {
        RoleGrants grants = GrantsCodec.parse(
                "{\"tables\":{\"*\":[\"read\"],\"documents\":[\"write\"]},\"actions\":[\"documents.approve\"]}");

        assertThat(GrantsAuthorities.toAuthorities(grants)).containsExactlyInAnyOrder(
                "table:*:read", "table:documents:write", "action:documents.approve");
        assertThat(GrantsAuthorities.toAuthorities(RoleGrants.empty())).isEmpty();
    }

    @Test
    void shouldMergeGrantsOfTwoRoles() {
        RoleGrants reader = GrantsCodec.parse("{\"tables\":{\"documents\":[\"read\"]},\"actions\":[\"a\"]}");
        RoleGrants writer = GrantsCodec.parse(
                "{\"tables\":{\"documents\":[\"write\"],\"dict_x\":[\"read\"]},\"actions\":[\"b\"]}");

        RoleGrants merged = GrantsAuthorities.merge(List.of(reader, writer));

        assertThat(merged.tables()).isEqualTo(Map.of(
                "documents", Set.of(GrantOp.READ, GrantOp.WRITE),
                "dict_x", Set.of(GrantOp.READ)));
        assertThat(merged.actions()).containsExactlyInAnyOrder("a", "b");
        assertThat(GrantsAuthorities.toAuthorities(merged))
                .contains("table:documents:read", "table:documents:write");
        assertThat(GrantsAuthorities.merge(List.of())).isEqualTo(RoleGrants.empty());
    }

    @Test
    void shouldCoverByHolderStar() {
        RoleGrants holder = GrantsCodec.parse(
                "{\"tables\":{\"*\":[\"read\"],\"a\":[\"write\"]},\"actions\":[\"x\"]}");

        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse(
                "{\"tables\":{\"b\":[\"read\"],\"a\":[\"read\",\"write\"]},\"actions\":[\"x\"]}"))).isTrue();
        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse("{\"tables\":{\"*\":[\"read\"]}}"))).isTrue();
        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse("{\"tables\":{\"b\":[\"write\"]}}"))).isFalse();
        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse("{\"tables\":{\"*\":[\"write\"]}}"))).isFalse();
        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse("{\"actions\":[\"y\"]}"))).isFalse();
        assertThat(GrantsAuthorities.covers(holder, RoleGrants.empty())).isTrue();
    }

    @Test
    void shouldNotCoverStarByConcreteTables() {
        RoleGrants holder = GrantsCodec.parse("{\"tables\":{\"a\":[\"read\"]}}");

        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse("{\"tables\":{\"*\":[\"read\"]}}"))).isFalse();
    }

    @Test
    void shouldAllowTableThroughStar() {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("table:*:read"), new SimpleGrantedAuthority("table:documents:write"));

        assertThat(GrantsAuthorities.allowsTable(authorities, "dict_country", GrantOp.READ)).isTrue();
        assertThat(GrantsAuthorities.allowsTable(authorities, "documents", GrantOp.WRITE)).isTrue();
        assertThat(GrantsAuthorities.allowsTable(authorities, "dict_country", GrantOp.WRITE)).isFalse();
        assertThat(GrantsAuthorities.allowsTable(List.of(new SimpleGrantedAuthority("table:documents:write")),
                "documents", GrantOp.READ)).isFalse();
    }

    @Test
    void shouldComputeAddedGrants() {
        RoleGrants previous = GrantsCodec.parse(
                "{\"tables\":{\"a\":[\"read\"],\"b\":[\"read\",\"write\"]},\"actions\":[\"x\"]}");
        RoleGrants updated = GrantsCodec.parse(
                "{\"tables\":{\"a\":[\"read\",\"write\"],\"c\":[\"read\"]},\"actions\":[\"x\",\"y\"]}");

        assertThat(GrantsAuthorities.added(previous, updated)).isEqualTo(GrantsCodec.parse(
                "{\"tables\":{\"a\":[\"write\"],\"c\":[\"read\"]},\"actions\":[\"y\"]}"));
        assertThat(GrantsAuthorities.added(updated, updated)).isEqualTo(RoleGrants.empty());
        assertThat(GrantsAuthorities.added(RoleGrants.empty(), updated)).isEqualTo(updated);
    }

    @Test
    void adminActionCoversAnyAction() {
        RoleGrants holder = GrantsCodec.parse("{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"admin\"]}");

        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse(
                "{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"documents.approve\",\"x.y\"]}"))).isTrue();
        assertThat(GrantsAuthorities.covers(holder, GrantsCodec.parse(
                "{\"tables\":{\"b\":[\"read\"]},\"actions\":[\"documents.approve\"]}"))).isFalse();
        assertThat(GrantsAuthorities.covers(GrantsCodec.parse("{\"actions\":[\"x\"]}"),
                GrantsCodec.parse("{\"actions\":[\"admin\"]}"))).isFalse();
    }

    @Test
    void adminAuthorityPassesAnyActionCheck() {
        List<SimpleGrantedAuthority> admin = List.of(new SimpleGrantedAuthority("action:admin"));
        List<SimpleGrantedAuthority> approver = List.of(new SimpleGrantedAuthority("action:documents.approve"));

        assertThat(GrantsAuthorities.hasAction(admin, "documents.approve")).isTrue();
        assertThat(GrantsAuthorities.hasAction(admin, "admin")).isTrue();
        assertThat(GrantsAuthorities.hasAction(approver, "documents.approve")).isTrue();
        assertThat(GrantsAuthorities.hasAction(approver, "documents.export")).isFalse();
        assertThat(GrantsAuthorities.hasAction(approver, "admin")).isFalse();
        assertThat(GrantsAuthorities.hasAction(admin, null)).isFalse();
    }
}
