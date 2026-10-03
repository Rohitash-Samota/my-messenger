package com.rohitsamota.my_messenger.services;

import java.util.Date;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {
	private final SecretKey signingKey;
	private final long expirationMs;

	public JwtService(
			@Value("${app.jwt.secret}") String base64Secret,
			@Value("${app.jwt.expiration-ms}") long expirationMs) {
		this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
		this.expirationMs = expirationMs;
	}

	public String generateToken(UserDetails userDetails) {
		Date issuedAt = new Date();
		Date expiresAt = new Date(issuedAt.getTime() + expirationMs);

		return Jwts.builder()
				.setSubject(userDetails.getUsername())
				.setIssuedAt(issuedAt)
				.setExpiration(expiresAt)
				.signWith(signingKey)
				.compact();
	}

	public String extractUsername(String token) {
		return extractClaim(token, Claims::getSubject);
	}

	public boolean isTokenValid(String token, UserDetails userDetails) {
		Claims claims = extractAllClaims(token);
		return claims.getSubject().equals(userDetails.getUsername())
			&& claims.getExpiration().after(new Date())
			&& userDetails.isEnabled()
			&& userDetails.isAccountNonExpired()
			&& userDetails.isAccountNonLocked()
			&& userDetails.isCredentialsNonExpired();
	}

	public long getExpirationSeconds() {
		return expirationMs / 1000;
	}

	private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
		return claimsResolver.apply(extractAllClaims(token));
	}

	private Claims extractAllClaims(String token) {
		return Jwts.parserBuilder()
				.setSigningKey(signingKey)
				.build()
				.parseClaimsJws(token)
				.getBody();
	}
}