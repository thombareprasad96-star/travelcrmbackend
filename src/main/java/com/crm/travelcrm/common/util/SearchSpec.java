package com.crm.travelcrm.common.util;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Case-insensitive "contains" ({@code LIKE %kw%}) across one or more STRING fields, OR-ed together —
 * shared by every list {@code Specification} so the lower/like/or boilerplate lives in exactly one
 * place instead of being re-typed (and drifting) per entity.
 *
 * <p>A null/blank keyword — or no fields — contributes nothing (an always-true predicate), so a
 * caller can AND it in unconditionally. Dotted field names ({@code "assignedUser.name"}) are walked
 * as a path, which for a to-one association becomes an implicit join.
 *
 * <pre>{@code
 *   public static Specification<Lead> search(String kw) {
 *       return SearchSpec.contains(kw, "customerName", "phone", "email", "leadCode");
 *   }
 * }</pre>
 */
public final class SearchSpec {

    private SearchSpec() {}

    public static <T> Specification<T> contains(String keyword, String... fields) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank() || fields == null || fields.length == 0) {
                return cb.conjunction();
            }
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            Predicate[] likes = new Predicate[fields.length];
            for (int i = 0; i < fields.length; i++) {
                Path<?> path = root;
                for (String part : fields[i].split("\\.")) {
                    path = path.get(part);
                }
                likes[i] = cb.like(cb.lower(path.as(String.class)), pattern);
            }
            return cb.or(likes);
        };
    }
}
