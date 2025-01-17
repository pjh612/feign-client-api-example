package com.example.api;

import com.example.processor.ClientExport;
import com.example.processor.Export;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Export
@ClientExport(exportPackage = "com.example.external")
public class TestController {

    @Export
    @GetMapping("/test")
    public String test() {
        return "hello";
    }
}
