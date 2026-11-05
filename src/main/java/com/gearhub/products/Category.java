package com.gearhub.products;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String slug;

    private String name;

    private int position;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    @OrderBy("position")
    @BatchSize(size = 32)
    private List<Criterion> criteria = new ArrayList<>();

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    @OrderBy("position")
    @BatchSize(size = 32)
    private List<SpecField> specFields = new ArrayList<>();

    protected Category() {
    }

    public Integer getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public List<Criterion> getCriteria() {
        return criteria;
    }

    public List<SpecField> getSpecFields() {
        return specFields;
    }
}
