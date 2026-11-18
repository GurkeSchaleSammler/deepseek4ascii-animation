package com.example.demo

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

@SpringBootTest
@AutoConfigureMockMvc
class FrontendSecurityTests @Autowired constructor(private val mvc: MockMvc) {
    @Test
    fun `frontend routes forward to Angular`() {
        listOf("/gallery", "/gallery/animation.html", "/prompt").forEach { path ->
            mvc.get(path).andExpect {
                status { isOk() }
                forwardedUrl("/index.html")
            }
        }
    }

    @Test
    fun `foreign origin is rejected`() {
        mvc.options("/ascii/custom") {
            header("Origin", "https://foreign.example")
            header("Access-Control-Request-Method", "POST")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `development origin can post with the CSRF header`() {
        mvc.options("/ascii/custom") {
            header("Origin", "http://localhost:4200")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type,x-xsrf-token")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:4200") }
        }
    }

    @Test
    fun `gallery does not allow cross origin writes`() {
        mvc.options("/ascii/gallery") {
            header("Origin", "http://localhost:4200")
            header("Access-Control-Request-Method", "POST")
        }.andExpect { status { isForbidden() } }
    }
}
