package com.charles.kb.controller;

import com.charles.kb.service.IngestService;
import com.charles.kb.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RagController {

    private final IngestService ingestService;
    private final RagService ragService;

    public RagController(IngestService ingestService, RagService ragService) {
        this.ingestService = ingestService;
        this.ragService = ragService;
    }

    public record AskRequest(String question) {
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(@RequestParam MultipartFile file) throws IOException {
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        int chunks = ingestService.ingest(file.getOriginalFilename(), text);
        return Map.of("file", file.getOriginalFilename(), "chunks", chunks);
    }

    @PostMapping("/ask")
    public RagService.Answer ask(@RequestBody AskRequest request) {
        return ragService.ask(request.question());
    }
}
