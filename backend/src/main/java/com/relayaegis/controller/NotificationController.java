package com.relayaegis.controller;

import com.relayaegis.auth.AuthenticatedUser;
import com.relayaegis.dto.ApiEnvelope;
import com.relayaegis.service.NotificationService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's acknowledged incident notifications. Read state only; incidents are never modified here. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    public record AcknowledgeRequest(List<String> incidentIds) {}

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/acknowledged")
    public ApiEnvelope<List<String>> acknowledged(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiEnvelope.of(notifications.acknowledged(user.id()), "live");
    }

    @PostMapping("/acknowledged")
    public ApiEnvelope<List<String>> acknowledge(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody AcknowledgeRequest request) {
        return ApiEnvelope.of(notifications.acknowledge(user.id(), request == null ? null : request.incidentIds()), "live");
    }
}
