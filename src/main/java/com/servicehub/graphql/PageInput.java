package com.servicehub.graphql;

import org.springframework.data.domain.Pageable;

public record PageInput(int page, int size) {
    public static final PageInput DEFAULT = new PageInput(0, 20);
    public Pageable pageable() {
        return com.servicehub.validation.Pagination.page(page, size);
    }
}
