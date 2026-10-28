package com.servicehub.validation;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Shared boundary validation before either transport reaches JPA's integer offset. */
public final class Pagination {
    public static final String INVALID = "Paginação inválida. Use página a partir de zero, tamanho entre 1 e 100 e offset permitido.";
    private Pagination() { }
    public static Pageable page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(INVALID);
        }
        return PageRequest.of(page, size, Sort.by("id"));
    }
}
