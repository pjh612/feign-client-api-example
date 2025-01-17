package com.example.external.base;

import java.lang.String;
import org.springframework.web.bind.annotation.GetMapping;

public interface TestClientBase {
  @GetMapping({"/test"})
  String test();
}
