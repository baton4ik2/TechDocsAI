package ru.techdocs.engineeringsystem;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "engineering_systems")
@Getter
@Setter
@NoArgsConstructor
public class EngineeringSystem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(nullable = false)
    private String name;

    private String code;

    private String description;
}
