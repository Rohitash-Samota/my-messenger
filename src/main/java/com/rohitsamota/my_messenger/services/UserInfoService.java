package com.rohitsamota.my_messenger.services;

import java.util.Locale;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class UserInfoService implements UserDetailsService {
    private final UserInfoRepository userRepository;

    public UserInfoService(UserInfoRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        var user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getUserRole().name()))
                .disabled(user.getStatus() != Status.ACTIVE)
                .build();
    }
}
