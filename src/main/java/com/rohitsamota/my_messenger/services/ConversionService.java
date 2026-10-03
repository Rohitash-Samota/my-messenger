package com.rohitsamota.my_messenger.services;

import org.springframework.stereotype.Service;

import com.rohitsamota.my_messenger.repo.ConversionRepoI;

@Service("conversionService")
public class ConversionService {
    private final ConversionRepoI conversionRepoI;
    public ConversionService(ConversionRepoI conversionRepoI){
        this.conversionRepoI = conversionRepoI;
    }
}