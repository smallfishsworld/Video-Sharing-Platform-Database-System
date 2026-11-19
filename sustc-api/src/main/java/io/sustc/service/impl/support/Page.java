package io.sustc.service.impl.support;

import java.util.Optional;

/**
 * A validated page request. Pages are numbered from 1, and the offset is computed in {@code long}
 * so that large page numbers cannot overflow.
 */
public record Page(int size, int number) {

    public static Optional<Page> of(int pageSize, int pageNum) {
        return pageSize > 0 && pageNum > 0 ? Optional.of(new Page(pageSize, pageNum)) : Optional.empty();
    }

    public long offset() {
        return (long) size * (number - 1);
    }
}
