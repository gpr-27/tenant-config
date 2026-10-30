package com.moveinsync.config.server.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import com.moveinsync.config.server.web.dto.DefinitionRequest;
import com.moveinsync.config.server.web.dto.DefinitionUpdate;
import com.moveinsync.config.server.web.dto.VersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/definitions")
@RequiredArgsConstructor
public class DefinitionController {

    private final DefinitionService definitions;

    @GetMapping
    public List<JsonNode> list() {
        List<JsonNode> json = new ArrayList<>();
        for (SettingDefinition definition : definitions.list()) {
            json.add(SnapshotCodec.definitionToJson(definition));
        }
        return json;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(@Valid @RequestBody DefinitionRequest request, Authentication auth) {
        return new VersionResponse(definitions.create(request, auth.getName()));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public VersionResponse update(@PathVariable String key, @RequestBody DefinitionUpdate request,
                                  Authentication auth) {
        return new VersionResponse(definitions.update(key, request, auth.getName()));
    }
}
