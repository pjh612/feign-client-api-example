package com.example.external;

import com.example.external.base.TestClientBase;
import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(
    name = "feign-client-apt-api"
)
public interface TestClient extends TestClientBase {
}
