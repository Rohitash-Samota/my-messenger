package com.rohitsamota.my_messenger.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rohitsamota.my_messenger.entity.Conversion;

public interface ConversionRepoI extends JpaRepository<Conversion, Long> {
}