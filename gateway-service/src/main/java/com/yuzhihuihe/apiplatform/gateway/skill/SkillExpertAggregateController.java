package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertAggregateResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Multipart aggregate endpoints used exclusively by the Expert WYSIWYG editor. */
@RestController
@RequestMapping("/console/gateway/admin/skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillExpertAggregateController {

    private static final Set<String> COMMAND_FIELDS = Arrays.stream(
                    ExpertAggregateCommand.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final SkillExpertAggregateService aggregates;
    private final ObjectMapper objectMapper;
    private final SkillCatalogEventService catalogEvents;

    public SkillExpertAggregateController(SkillExpertAggregateService aggregates) {
        this(aggregates, new ObjectMapper(), new SkillCatalogEventService());
    }

    public SkillExpertAggregateController(
            SkillExpertAggregateService aggregates, ObjectMapper objectMapper) {
        this(aggregates, objectMapper, new SkillCatalogEventService());
    }

    @Autowired
    public SkillExpertAggregateController(
            SkillExpertAggregateService aggregates,
            ObjectMapper objectMapper,
            SkillCatalogEventService catalogEvents) {
        this.aggregates = aggregates;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.catalogEvents = catalogEvents;
    }

    @PostMapping(value = "/experts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ExpertAggregateResponse> create(
            @RequestPart(name = "data", required = false) String data,
            @RequestPart(name = "json", required = false) String json,
            @RequestPart(name = "metadata", required = false) String metadata,
            @RequestPart(name = "payload", required = false) String payload,
            @RequestPart(name = "icon", required = false) FilePart icon,
            @RequestPart(name = "illustration", required = false) FilePart illustration,
            @RequestPart(name = "exampleImage", required = false) FilePart exampleImage,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(aggregates.create(
                parse(first(data, json, metadata, payload)), icon, illustration,
                exampleImage, actor(jwt)));
    }

    @PutMapping(
            value = "/{id}/expert-presentation",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<ExpertAggregateResponse> update(
            @PathVariable UUID id,
            @RequestPart(name = "data", required = false) String data,
            @RequestPart(name = "json", required = false) String json,
            @RequestPart(name = "metadata", required = false) String metadata,
            @RequestPart(name = "payload", required = false) String payload,
            @RequestPart(name = "icon", required = false) FilePart icon,
            @RequestPart(name = "illustration", required = false) FilePart illustration,
            @RequestPart(name = "exampleImage", required = false) FilePart exampleImage,
            @AuthenticationPrincipal Jwt jwt) {
        return catalogEvents.publishAfter(aggregates.update(
                id, parse(first(data, json, metadata, payload)), icon, illustration,
                exampleImage, actor(jwt)));
    }

    private ExpertAggregateCommand parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "expert_aggregate_json_required");
        }
        try {
            return objectMapper.readValue(json, ExpertAggregateCommand.class);
        } catch (JsonMappingException error) {
            // Jackson's exception text can contain submitted values. Return only a field name
            // known to the command schema; syntax errors still point to the multipart data part.
            String field = error.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(candidate -> candidate != null && COMMAND_FIELDS.contains(candidate))
                    .findFirst().orElse("data");
            throw new ExpertFieldValidationException("expert_aggregate_json_invalid", field);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "expert_aggregate_json_invalid", error);
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static UUID actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) return null;
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
