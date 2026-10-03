package com.rohitsamota.my_messenger.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/messages")
@Validated
public class MessageController {
    // get all message by desc order using currser a
}