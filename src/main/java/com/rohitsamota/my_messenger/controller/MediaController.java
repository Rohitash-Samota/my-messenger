package com.rohitsamota.my_messenger.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rohitsamota.my_messenger.dto.MediaUploadResponseDto;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.services.MediaStorageService;

import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/v1/api/media")
@Validated
public class MediaController {
    private final MediaStorageService mediaStorageService;

    public MediaController(MediaStorageService mediaStorageService) {
        this.mediaStorageService = mediaStorageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaUploadResponseDto> upload(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @Positive Long conversationId,
            @RequestParam MessageType messageType,
            @RequestParam("file") MultipartFile file) {
        MediaUploadResponseDto response = mediaStorageService.store(
                userDetails.getUsername(), conversationId, messageType, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String mediaId) {
        var media = mediaStorageService.load(userDetails.getUsername(), mediaId);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(media.originalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(media.resource());
    }
}
