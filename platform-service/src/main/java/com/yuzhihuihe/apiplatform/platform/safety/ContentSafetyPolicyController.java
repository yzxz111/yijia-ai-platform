package com.yuzhihuihe.apiplatform.platform.safety;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/gateway/content-safety")
public class ContentSafetyPolicyController {

    private final ContentSafetyPolicyService policyService;

    public ContentSafetyPolicyController(ContentSafetyPolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/policy")
    ContentSafetyPolicyService.PolicySnapshotView policy() {
        return policyService.publishedSnapshot();
    }
}
