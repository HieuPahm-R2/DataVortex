package io.datavortex.adinsight.shared.domain;

public record PageQuery(int page, int size) {
    public PageQuery {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidInputException(
                    "page must be non-negative, size 1..100 and offset within integer range");
        }
    }
}
