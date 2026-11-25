package com.yuzhihuihe.apiplatform.platform.legal;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向控制台提供已发布的服务条款与隐私政策。这些路径位于
 * {@code /console/platform/**} 之外，因为该前缀要求调用方已通过身份认证。
 */
@RestController
@RequestMapping("/console/legal")
public class LegalDocumentController {

    private final LegalDocumentService service;

    public LegalDocumentController(LegalDocumentService service) {
        this.service = service;
    }

    @GetMapping("/{kind}")
    LegalDocumentService.PublishedDocument current(@PathVariable String kind) {
        return service.published(kind.toUpperCase(Locale.ROOT));
    }
}
