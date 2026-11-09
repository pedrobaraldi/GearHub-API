package com.gearhub.products;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Campo da ficha técnica que o formulário de post pede para uma categoria. */
@Entity
@Table(name = "spec_fields")
public class SpecField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "key")
    private String key;

    private String label;

    private boolean required;

    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> options = List.of();

    protected SpecField() {
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }

    public List<String> getOptions() {
        return options;
    }

    public int getPosition() {
        return position;
    }
}
