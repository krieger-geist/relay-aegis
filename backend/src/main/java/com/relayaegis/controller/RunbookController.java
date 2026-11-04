package com.relayaegis.controller;

import com.relayaegis.diagnostics.runbook.RunbookCatalog;
import com.relayaegis.dto.ApiEnvelope;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The YAML runbooks in effect on this instance and why any file was rejected. */
@RestController
@RequestMapping("/api/runbooks")
public class RunbookController {
    private final RunbookCatalog catalog;

    public RunbookController(RunbookCatalog catalog) { this.catalog = catalog; }

    @GetMapping
    public ApiEnvelope<List<RunbookCatalog.Status>> list() { return ApiEnvelope.of(catalog.statuses(), "live"); }

    /** Re-reads the runbook files now instead of waiting for the next periodic reload. */
    @PostMapping("/reload")
    public ApiEnvelope<List<RunbookCatalog.Status>> reload() { return ApiEnvelope.of(catalog.reload(), "live"); }
}
