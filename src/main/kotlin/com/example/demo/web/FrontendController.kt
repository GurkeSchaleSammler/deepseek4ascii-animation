package com.example.demo.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class FrontendController {
    @GetMapping("/gallery", "/gallery/{id}", "/prompt", "/loadingscreen")
    fun frontend(): String = "forward:/index.html"
}
