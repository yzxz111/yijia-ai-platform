package com.yuzhihuihe.apiplatform.platform.identity;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwkSetController {

    private final JWKSet publicKeys;

    public JwkSetController(JWKSet jwtPublicJwkSet) {
        this.publicKeys = jwtPublicJwkSet;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwkSet() {
        return publicKeys.toJSONObject();
    }
}
