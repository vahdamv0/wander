package com.wander.place;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One thing somebody wrote about a place.
 *
 * Several per place, ordered, and replaced as a set rather than edited
 * individually — the same bargain `ExpenseShare` makes, and for the same reason:
 * a list written whole has no half-applied state, and there is nothing here worth
 * addressing by id from outside.
 */
@Entity
@Table(name = "place_note")
public class PlaceNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false)
    private int position;

    protected PlaceNote() {
        // JPA
    }

    PlaceNote(Place place, String body, int position) {
        this.place = place;
        this.body = body;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public String getBody() {
        return body;
    }

    void setBody(String body) {
        this.body = body;
    }

    public int getPosition() {
        return position;
    }

    void setPosition(int position) {
        this.position = position;
    }
}
