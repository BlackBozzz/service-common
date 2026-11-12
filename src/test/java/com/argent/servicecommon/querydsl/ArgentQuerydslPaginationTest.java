package com.argent.servicecommon.querydsl;

import com.argent.servicecommon.auth.entity.QUser;
import com.querydsl.core.DefaultQueryMetadata;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQuery;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArgentQuerydslPaginationTest {

    @SuppressWarnings("unchecked")
    @Test
    void paginatesAndCountsUsingIndependentQueries() {
        JPAQuery<String> content = mock(JPAQuery.class);
        JPAQuery<Long> count = mock(JPAQuery.class);
        when(content.offset(2L)).thenReturn(content);
        when(content.limit(2L)).thenReturn(content);
        when(content.fetch()).thenReturn(List.of("third", "fourth"));
        when(count.fetchOne()).thenReturn(7L);

        var page = ArgentQuerydsl.paginate(content, count, PageRequest.of(1, 2));

        assertThat(page.getContent()).containsExactly("third", "fourth");
        assertThat(page.getTotalElements()).isEqualTo(7L);
        assertThat(page.getTotalPages()).isEqualTo(4);
        verify(content).offset(2L);
        verify(content).limit(2L);
        verify(count).fetchOne();
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Test
    void automaticallyCountsWhenPageIsFull() {
        JPAQuery<String> content = mock(JPAQuery.class);
        when(content.getMetadata()).thenReturn(new DefaultQueryMetadata());
        when(content.offset(0L)).thenReturn(content);
        when(content.limit(2L)).thenReturn(content);
        when(content.fetch()).thenReturn(List.of("first", "second"));
        when(content.fetchCount()).thenReturn(17L);

        var page = ArgentQuerydsl.paginate(content, PageRequest.of(0, 2));

        assertThat(page.getContent()).containsExactly("first", "second");
        assertThat(page.getTotalElements()).isEqualTo(17L);
        assertThat(page.getTotalPages()).isEqualTo(9);
        verify(content).fetchCount();
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Test
    void infersTotalFromShortPageWithoutCountQuery() {
        JPAQuery<String> content = mock(JPAQuery.class);
        when(content.getMetadata()).thenReturn(new DefaultQueryMetadata());
        when(content.offset(20L)).thenReturn(content);
        when(content.limit(10L)).thenReturn(content);
        when(content.fetch()).thenReturn(List.of("last"));

        var page = ArgentQuerydsl.paginate(content, PageRequest.of(2, 10));

        assertThat(page.getTotalElements()).isEqualTo(21L);
        assertThat(page.getContent()).containsExactly("last");
        verify(content, never()).fetchCount();
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Test
    void stillCountsWhenRequestedPageIsEmptyPastTheEnd() {
        JPAQuery<String> content = mock(JPAQuery.class);
        when(content.getMetadata()).thenReturn(new DefaultQueryMetadata());
        when(content.offset(30L)).thenReturn(content);
        when(content.limit(10L)).thenReturn(content);
        when(content.fetch()).thenReturn(List.of());
        when(content.fetchCount()).thenReturn(23L);

        var page = ArgentQuerydsl.paginate(content, PageRequest.of(3, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(23L);
        verify(content).fetchCount();
    }

    @Test
    void automaticModeRejectsGroupByAndDistinct() {
        var qUser = QUser.user;
        var grouped = new JPAQuery<String>()
                .select(qUser.login)
                .from(qUser)
                .groupBy(qUser.login);
        var distinct = new JPAQuery<String>()
                .select(qUser.login)
                .from(qUser)
                .distinct();

        assertThatThrownBy(() -> ArgentQuerydsl.paginate(grouped, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countQuery");
        assertThatThrownBy(() -> ArgentQuerydsl.paginate(distinct, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("countQuery");
    }

    @SuppressWarnings("unchecked")
    @Test
    void requiresWhitelistedSortFields() {
        JPAQuery<String> content = mock(JPAQuery.class);
        JPAQuery<Long> count = mock(JPAQuery.class);
        Pageable pageable = PageRequest.of(0, 10, Sort.by("unknown"));
        assertThatThrownBy(() -> ArgentQuerydsl.paginate(content, count, pageable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Test
    void automaticallyCountsWithAllowedSort() {
        JPAQuery<String> content = mock(JPAQuery.class);
        when(content.getMetadata()).thenReturn(new DefaultQueryMetadata());
        when(content.orderBy(any(OrderSpecifier[].class))).thenReturn(content);
        when(content.offset(0L)).thenReturn(content);
        when(content.limit(2L)).thenReturn(content);
        when(content.fetch()).thenReturn(List.of("b", "a"));
        when(content.fetchCount()).thenReturn(10L);

        var page = ArgentQuerydsl.paginate(
                content,
                PageRequest.of(0, 2, Sort.by(Sort.Order.desc("login"))),
                Map.of("login", QUser.user.login));

        assertThat(page.getTotalElements()).isEqualTo(10L);
        verify(content).orderBy(any(OrderSpecifier[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void appliesSortAndReturnsUnpagedContentWithoutCount() {
        JPAQuery<String> content = mock(JPAQuery.class);
        JPAQuery<Long> count = mock(JPAQuery.class);
        when(content.orderBy(any(OrderSpecifier[].class))).thenReturn(content);
        when(content.fetch()).thenReturn(List.of("b", "a"));

        var page = ArgentQuerydsl.paginate(
                content, count,
                Pageable.unpaged(Sort.by(Sort.Order.desc("login"))),
                Map.of("login", QUser.user.login));

        assertThat(page.getContent()).containsExactly("b", "a");
        assertThat(page.getTotalElements()).isEqualTo(2L);
        verify(content).orderBy(any(OrderSpecifier[].class));
        verify(count, never()).fetchOne();
    }
}
