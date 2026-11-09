package com.example.library.infrastructure.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/")
    public String reader() { return "reader"; }

    @GetMapping("/admin")
    public String admin() { return "admin"; }
}
