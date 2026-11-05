package com.gearhub.reviews;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Posts e reviews têm as mesmas interações em tabelas paralelas. Os nomes são fixos: nunca vêm do usuário. */
public enum ContentKind {
    POST("posts", "post_likes", "saved_posts", "post_id"),
    REVIEW("reviews", "review_likes", "saved_reviews", "review_id");

    private final String table;
    private final String likesTable;
    private final String savedTable;
    private final String foreignKey;

    ContentKind(String table, String likesTable, String savedTable, String foreignKey) {
        this.table = table;
        this.likesTable = likesTable;
        this.savedTable = savedTable;
        this.foreignKey = foreignKey;
    }

    public static ContentKind fromPath(String path) {
        for (ContentKind kind : values()) {
            if (kind.table.equals(path)) {
                return kind;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Publicação não encontrada");
    }

    public String table() {
        return table;
    }

    public String likesTable() {
        return likesTable;
    }

    public String savedTable() {
        return savedTable;
    }

    public String foreignKey() {
        return foreignKey;
    }
}
