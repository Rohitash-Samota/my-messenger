package com.rohitsamota.my_messenger.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.rohitsamota.my_messenger.dto.LoginRequestDto;
import com.rohitsamota.my_messenger.dto.LoginResponseDto;
import com.rohitsamota.my_messenger.dto.RegisterRequestDto;
import com.rohitsamota.my_messenger.dto.RegisterResponseDto;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.enums.UserRole;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class AuthService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:refresh:";

    private final UserInfoRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final UserInfoService userInfoService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final long refreshExpirationMs;

    public AuthService(
            UserInfoRepository userRepository,
            StringRedisTemplate redisTemplate,
            UserInfoService userInfoService,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.userInfoService = userInfoService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    @Transactional
    public RegisterResponseDto register(RegisterRequestDto request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setMobileNumber(request.mobileNumber());
        user.setStatus(Status.ACTIVE);
        user.setUserRole(UserRole.USER);
        user = userRepository.save(user);
        return createRegisterResponse(user);
    }

    @Transactional
    public LoginResponseDto login(LoginRequestDto request) {
        String normalizedEmail = normalizeEmail(request.email());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.password()));
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return createLoginResponse(user);
    }

    public LoginResponseDto refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        String tokenKey = refreshTokenKey(rawRefreshToken);
        String email = redisTemplate.opsForValue().getAndDelete(tokenKey);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is expired or invalid");
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
        if (user.getStatus() != Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account is disabled");
        }

        return createLoginResponse(user);
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            redisTemplate.delete(refreshTokenKey(rawRefreshToken));
        }
    }

    private RegisterResponseDto createRegisterResponse(User user) {
        UserDetails userDetails = userInfoService.loadUserByUsername(user.getEmail());
        String rawRefreshToken = createRefreshTokenValue();
        redisTemplate.opsForValue().set(
                refreshTokenKey(rawRefreshToken),
                user.getEmail(),
                Duration.ofMillis(refreshExpirationMs));

        return new RegisterResponseDto(
                jwtService.generateToken(userDetails),
                "Bearer",
                jwtService.getExpirationSeconds(),
                rawRefreshToken,
                refreshExpirationMs / 1000);
    }

            private LoginResponseDto createLoginResponse(User user) {
            UserDetails userDetails = userInfoService.loadUserByUsername(user.getEmail());
            String rawRefreshToken = createRefreshTokenValue();
            redisTemplate.opsForValue().set(
                refreshTokenKey(rawRefreshToken),
                user.getEmail(),
                Duration.ofMillis(refreshExpirationMs));

            return new LoginResponseDto(
                jwtService.generateToken(userDetails),
                "Bearer",
                jwtService.getExpirationSeconds(),
                rawRefreshToken,
                refreshExpirationMs / 1000);
            }

    private String createRefreshTokenValue() {
        byte[] randomBytes = new byte[64];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String refreshTokenKey(String rawRefreshToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
            return REFRESH_TOKEN_KEY_PREFIX + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}