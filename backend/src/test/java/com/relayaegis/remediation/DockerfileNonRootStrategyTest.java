package com.relayaegis.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.relayaegis.dto.AssessmentDtos.AssessmentFindingDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerfileNonRootStrategyTest {
    private static AssessmentFindingDto finding(String title, String file) {
        return new AssessmentFindingDto("F-1", "security", title, "medium", "medium", file, 1, "e", "x", "r", "unverified_candidate");
    }

    @Test
    void supportsOnlyTheRootFindingOnDockerfiles() {
        assertTrue(DockerfileNonRootStrategy.supports(finding("Container runs as root", "Dockerfile")));
        assertTrue(DockerfileNonRootStrategy.supports(finding("Container runs as root", "services/api/Dockerfile")));
        assertTrue(DockerfileNonRootStrategy.supports(finding("Container runs as root", "deploy/api.dockerfile")));
        assertFalse(DockerfileNonRootStrategy.supports(finding("Base image not pinned", "Dockerfile")));
        assertFalse(DockerfileNonRootStrategy.supports(finding("Container runs as root", "docker-compose.yml")));
        assertFalse(DockerfileNonRootStrategy.supports(finding("Possible SQL query concatenation", "src/Dao.java")), "SQL fixes stay manual");
    }

    @Test
    void nodeImageUsesTheBuiltInNodeUserBeforeCmd() throws Exception {
        List<String> lines = List.of("FROM node:20-alpine", "WORKDIR /app", "COPY . .", "RUN npm ci", "CMD [\"node\", \"server.js\"]");
        DockerfileNonRootStrategy.Proposal proposal = DockerfileNonRootStrategy.propose(lines);
        assertEquals("node", proposal.user());
        assertEquals(List.of("FROM node:20-alpine", "WORKDIR /app", "COPY . .", "RUN npm ci", DockerfileNonRootStrategy.COMMENT, "USER node",
                "CMD [\"node\", \"server.js\"]"), proposal.newLines());
    }

    @Test
    void alpineAndDebianImagesCreateASystemUserWithTheRightTools() throws Exception {
        List<String> alpine = DockerfileNonRootStrategy.propose(List.of("FROM python:3.12-alpine", "ENTRYPOINT [\"python\", \"app.py\"]")).newLines();
        assertTrue(alpine.contains("RUN addgroup -S app && adduser -S -G app app"));
        assertEquals("USER app", alpine.get(alpine.size() - 2));
        List<String> debian = DockerfileNonRootStrategy.propose(List.of("FROM eclipse-temurin:21-jre", "COPY app.jar /app.jar")).newLines();
        assertTrue(debian.get(debian.size() - 2).startsWith("RUN groupadd --system app && useradd --system"));
        assertEquals("USER app", debian.get(debian.size() - 1), "no CMD: appended at the end");
    }

    @Test
    void multiStageBuildChangesOnlyTheFinalStage() throws Exception {
        List<String> lines = List.of("FROM maven:3.9-eclipse-temurin-21 AS build", "RUN mvn -q package", "FROM gcr.io/distroless/java21-debian12",
                "COPY --from=build /app.jar /app.jar", "CMD [\"/app.jar\"]");
        List<String> out = DockerfileNonRootStrategy.propose(lines).newLines();
        assertEquals("USER nonroot", out.get(5));
        assertEquals(lines.subList(0, 4), out.subList(0, 4), "the build stage is untouched");
    }

    @Test
    void refusesWhenTheChangeIsNotMechanicallySafe() {
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("FROM nginx:1.27", "EXPOSE 80")), "privileged port");
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("ARG BASE", "FROM $BASE")), "unknown image");
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("FROM scratch", "COPY app /app")), "unknown family");
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("COPY . .")), "no FROM");
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("FROM node:20", "USER node")), "already non-root");
        assertThrows(RemediationException.class, () -> DockerfileNonRootStrategy.propose(List.of("FROM node:20", "RUN apt-get update && \\")), "continuation");
    }

    @Test
    void anExplicitRootUserIsReplacedByAddingANonRootUserAfterIt() throws Exception {
        List<String> out = DockerfileNonRootStrategy.propose(List.of("FROM node:20", "USER root", "RUN chown -R node /app", "CMD [\"node\"]")).newLines();
        assertEquals("USER node", out.get(4));
    }
}
