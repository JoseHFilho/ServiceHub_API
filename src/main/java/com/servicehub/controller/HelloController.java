package com.servicehub.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "ServiceHub API está no ar! 🚀";
    }

    @GetMapping("/status")
    public String status() {
        return "OK - v1.0";
    }
}